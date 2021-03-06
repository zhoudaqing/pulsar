/**
 * Copyright 2016 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.pulsar.client.impl;

import static com.google.common.base.Preconditions.checkArgument;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Queues;
import com.yahoo.pulsar.client.api.CompressionType;
import com.yahoo.pulsar.client.api.Message;
import com.yahoo.pulsar.client.api.MessageId;
import com.yahoo.pulsar.client.api.Producer;
import com.yahoo.pulsar.client.api.ProducerConfiguration;
import com.yahoo.pulsar.client.api.PulsarClientException;
import com.yahoo.pulsar.common.api.Commands;
import com.yahoo.pulsar.common.api.proto.PulsarApi;
import com.yahoo.pulsar.common.api.proto.PulsarApi.MessageMetadata;
import com.yahoo.pulsar.common.compression.CompressionCodec;
import com.yahoo.pulsar.common.compression.CompressionCodecProvider;
import com.yahoo.pulsar.common.util.XXHashChecksum;

import io.netty.buffer.ByteBuf;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;

public class ProducerImpl extends ProducerBase implements TimerTask {

    // Producer id, used to identify a producer within a single connection
    private final long producerId;

    // Variable is used through the atomic updater
    @SuppressWarnings("unused")
    private volatile long msgIdGenerator = 0;

    private final BlockingQueue<OpSendMsg> pendingMessages;
    private final BlockingQueue<OpSendMsg> pendingCallbacks;
    private final Semaphore semaphore;
    private volatile Timeout sendTimeout = null;
    private long createProducerTimeout;
    private final int maxNumMessagesInBatch;
    private final BatchMessageContainer batchMessageContainer;

    // Globally unique producer name
    private String producerName;

    private String connectionId;
    private String connectedSince;
    private final int partitionIndex;

    private final ProducerStats stats;

    private final CompressionCodec compressor;

    private static final AtomicLongFieldUpdater<ProducerImpl> msgIdGeneratorUpdater = AtomicLongFieldUpdater
            .newUpdater(ProducerImpl.class, "msgIdGenerator");

    public ProducerImpl(PulsarClientImpl client, String topic, String producerName, ProducerConfiguration conf,
            CompletableFuture<Producer> producerCreatedFuture, int partitionIndex) {
        super(client, topic, conf, producerCreatedFuture);
        this.producerId = client.newProducerId();
        this.producerName = producerName;
        this.partitionIndex = partitionIndex;
        this.pendingMessages = Queues.newArrayBlockingQueue(conf.getMaxPendingMessages());
        this.pendingCallbacks = Queues.newArrayBlockingQueue(conf.getMaxPendingMessages());
        this.semaphore = new Semaphore(conf.getMaxPendingMessages(), true);
        this.compressor = CompressionCodecProvider
                .getCompressionCodec(convertCompressionType(conf.getCompressionType()));

        if (conf.getSendTimeoutMs() > 0) {
            sendTimeout = client.timer().newTimeout(this, conf.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
        }

        this.createProducerTimeout = System.currentTimeMillis() + client.getConfiguration().getOperationTimeoutMs();
        if (conf.getBatchingEnabled()) {
            this.maxNumMessagesInBatch = conf.getBatchingMaxMessages();
            this.batchMessageContainer = new BatchMessageContainer(maxNumMessagesInBatch,
                    convertCompressionType(conf.getCompressionType()), topic, producerName);
        } else {
            this.maxNumMessagesInBatch = 1;
            this.batchMessageContainer = null;
        }
        if (client.getConfiguration().getStatsIntervalSeconds() > 0) {
            stats = new ProducerStats(client, conf, this);
        } else {
            stats = ProducerStats.PRODUCER_STATS_DISABLED;
        }
        grabCnx();
    }

    private boolean isBatchMessagingEnabled() {
        return conf.getBatchingEnabled();
    }

    @Override
    public CompletableFuture<MessageId> sendAsync(Message message) {
        CompletableFuture<MessageId> future = new CompletableFuture<>();

        sendAsync(message, new SendCallback() {
            SendCallback nextCallback = null;
            long createdAt = System.nanoTime();

            @Override
            public CompletableFuture<MessageId> getFuture() {
                return future;
            }

            @Override
            public SendCallback getNextSendCallback() {
                return nextCallback;
            }

            @Override
            public void sendComplete(Exception e) {
                if (e != null) {
                    stats.incrementSendFailed();
                    future.completeExceptionally(e);
                } else {
                    future.complete(message.getMessageId());
                    stats.incrementNumAcksReceived(System.nanoTime() - createdAt);
                }
                while (nextCallback != null) {
                    SendCallback sendCallback = nextCallback;
                    if (e != null) {
                        stats.incrementSendFailed();
                        sendCallback.getFuture().completeExceptionally(e);
                    } else {
                        sendCallback.getFuture().complete(message.getMessageId());
                        stats.incrementNumAcksReceived(System.nanoTime() - createdAt);
                    }
                    nextCallback = nextCallback.getNextSendCallback();
                    sendCallback = null;
                }
            }

            @Override
            public void addCallback(SendCallback scb) {
                nextCallback = scb;
            }
        });
        return future;
    }

    public void sendAsync(Message message, SendCallback callback) {
        checkArgument(message instanceof MessageImpl);

        if (!isValidProducerState(callback)) {
            return;
        }

        if (!canEnqueueRequest(callback)) {
            return;
        }

        MessageImpl msg = (MessageImpl) message;
        MessageMetadata.Builder msgMetadata = msg.getMessageBuilder();
        ByteBuf payload = msg.getDataBuffer();

        if (!msgMetadata.hasChecksum()) {
            msgMetadata.setChecksum(XXHashChecksum.computeChecksum(payload));
        }

        // If compression is enabled, we are compressing, otherwise it will simply use the same buffer
        int uncompressedSize = payload.readableBytes();
        ByteBuf compressedPayload = payload;
        // batch will be compressed when closed
        if (!isBatchMessagingEnabled()) {
            compressedPayload = compressor.encode(payload);
            payload.release();
        }

        if (!msg.isReplicated() && msgMetadata.hasProducerName()) {
            callback.sendComplete(new PulsarClientException.InvalidMessageException("Cannot re-use the same message"));
            compressedPayload.release();
            return;
        }

        try {
            synchronized (this) {
                long sequenceId = msgIdGeneratorUpdater.getAndIncrement(this);
                if (!msgMetadata.hasPublishTime()) {
                    msgMetadata.setPublishTime(System.currentTimeMillis());

                    checkArgument(!msgMetadata.hasProducerName());
                    checkArgument(!msgMetadata.hasSequenceId());

                    msgMetadata.setProducerName(producerName);
                    msgMetadata.setSequenceId(sequenceId);
                    if (conf.getCompressionType() != CompressionType.NONE) {
                        msgMetadata.setCompression(convertCompressionType(conf.getCompressionType()));
                        msgMetadata.setUncompressedSize(uncompressedSize);
                    }
                }

                if (isBatchMessagingEnabled()) {
                    // handle boundary cases where message being added would exceed
                    // batch size and/or max message size
                    if (batchMessageContainer.hasSpaceInBatch(msg)) {
                        batchMessageContainer.add(msg, callback);
                        payload.release();
                        if (batchMessageContainer.numMessagesInBatch == maxNumMessagesInBatch
                                || batchMessageContainer.currentBatchSizeBytes >= BatchMessageContainer.MAX_MESSAGE_BATCH_SIZE_BYTES) {
                            batchMessageAndSend();
                        }
                    } else {
                        doBatchSendAndAdd(msg, callback, payload);
                    }
                } else {
                    ByteBuf cmd = Commands.newSend(producerId, sequenceId, 1, msgMetadata.build(), compressedPayload);
                    msgMetadata.recycle();

                    final OpSendMsg op = OpSendMsg.create(msg, cmd, sequenceId, callback);
                    op.setNumMessagesInBatch(1);
                    op.setBatchSizeByte(payload.readableBytes());
                    pendingMessages.put(op);

                    if (isConnected()) {
                        // If we do have a connection, the message is sent immediately, otherwise we'll try again once a
                        // new
                        // connection is established
                        cmd.retain();
                        cnx().ctx().channel().eventLoop().execute(WriteInEventLoopCallback.create(this, cnx(), op));
                        stats.updateNumMsgsSent(op.numMessagesInBatch, op.batchSizeByte);
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("[{}] [{}] Connection is not ready -- sequenceId {}", topic, producerName,
                                    sequenceId);
                        }
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            semaphore.release();
            callback.sendComplete(new PulsarClientException(ie));
        } catch (Throwable t) {
            semaphore.release();
            callback.sendComplete(new PulsarClientException(t));
        }
    }

    private void doBatchSendAndAdd(MessageImpl msg, SendCallback callback, ByteBuf payload) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] [{}] Closing out batch to accomodate large message with size {}", topic, producerName,
                    msg.getDataBuffer().readableBytes());
        }
        batchMessageAndSend();
        batchMessageContainer.add(msg, callback);
        payload.release();
    }

    private boolean isValidProducerState(SendCallback callback) {
        switch (state.get()) {
        case Ready:
            // OK
        case Connecting:
            // We are OK to queue the messages on the client, it will be sent to the broker once we get the connection
            return true;
        case Closing:
        case Closed:
            callback.sendComplete(new PulsarClientException.AlreadyClosedException("Producer already closed"));
            return false;
        case Failed:
        case Uninitialized:
        default:
            callback.sendComplete(new PulsarClientException.NotConnectedException());
            return false;
        }
    }

    private boolean canEnqueueRequest(SendCallback callback) {
        try {
            if (conf.getBlockIfQueueFull()) {
                semaphore.acquire();
            } else {
                if (!semaphore.tryAcquire()) {
                    callback.sendComplete(
                            new PulsarClientException.ProducerQueueIsFullError("Producer send queue is full"));
                    return false;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.sendComplete(new PulsarClientException(e));
            return false;
        }

        return true;
    }

    private static final class WriteInEventLoopCallback implements Runnable {
        private ProducerImpl producer;
        private ClientCnx cnx;
        private OpSendMsg op;

        static WriteInEventLoopCallback create(ProducerImpl producer, ClientCnx cnx, OpSendMsg op) {
            WriteInEventLoopCallback c = RECYCLER.get();
            c.producer = producer;
            c.cnx = cnx;
            c.op = op;
            return c;
        }

        @Override
        public void run() {
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] Sending message cnx {}, sequenceId {}", producer.topic, producer.producerName, cnx,
                        op.sequenceId);
            }

            try {
                cnx.ctx().writeAndFlush(op.cmd, cnx.ctx().voidPromise());
            } finally {
                recycle();
            }
        }

        private void recycle() {
            producer = null;
            cnx = null;
            op = null;
            RECYCLER.recycle(this, recyclerHandle);
        }

        private final Handle recyclerHandle;

        private WriteInEventLoopCallback(Handle recyclerHandle) {
            this.recyclerHandle = recyclerHandle;
        }

        private static final Recycler<WriteInEventLoopCallback> RECYCLER = new Recycler<WriteInEventLoopCallback>() {
            @Override
            protected WriteInEventLoopCallback newObject(Handle handle) {
                return new WriteInEventLoopCallback(handle);
            }
        };
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        if (state.get() == State.Closing || state.get() == State.Closed) {
            return CompletableFuture.completedFuture(null);
        }

        if (!isConnected()) {
            log.info("[{}] [{}] Closed Producer (not connected)", topic, producerName);
            state.set(State.Closed);
            client.cleanupProducer(this);
            pendingMessages.forEach(msg -> msg.cmd.release());
            return CompletableFuture.completedFuture(null);
        }

        state.set(State.Closing);

        Timeout timeout = sendTimeout;
        if (timeout != null) {
            timeout.cancel();
        }
        timeout = stats.getStatTimeout();
        if (timeout != null) {
            timeout.cancel();
        }

        long requestId = client.newRequestId();
        ByteBuf cmd = Commands.newCloseProducer(producerId, requestId);

        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        ClientCnx cnx = cnx();
        cnx.sendRequestWithId(cmd, requestId).handle((v, exception) -> {
            cnx.removeProducer(producerId);
            if (exception == null || !cnx.ctx().channel().isActive()) {
                // Either we've received the success response for the close producer command from the broker, or the
                // connection did break in the meantime. In any case, the producer is gone.
                log.info("[{}] [{}] Closed Producer", topic, producerName);
                state.set(State.Closed);
                pendingMessages.forEach(msg -> msg.cmd.release());
                closeFuture.complete(null);
                client.cleanupProducer(this);
            } else {
                closeFuture.completeExceptionally(exception);
            }

            return null;
        });

        return closeFuture;
    }

    @Override
    public boolean isConnected() {
        return clientCnx.get() != null && (state.get() == State.Ready);
    }

    public boolean isWritable() {
        ClientCnx cnx = clientCnx.get();
        return cnx != null && cnx.channel().isWritable();
    }

    void ackReceived(ClientCnx cnx, long sequenceId, long ledgerId, long entryId) {
        OpSendMsg op = null;
        boolean callback = false;
        synchronized (this) {
            op = pendingMessages.peek();
            if (op == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] [{}] Got ack for timed out msg {}", topic, producerName, sequenceId);
                }
                return;
            }

            long expectedSequenceId = op.sequenceId;
            if (sequenceId > expectedSequenceId) {
                log.warn("[{}] [{}] Got ack for msg. expecting: {} - got: {} - queue-size: {}", topic, producerName,
                        expectedSequenceId, sequenceId, pendingMessages.size());
                // Force connection closing so that messages can be retransmitted in a new connection
                cnx.channel().close();
            } else if (sequenceId < expectedSequenceId) {
                // Ignoring the ack since it's referring to a message that has already timed out.
                if (log.isDebugEnabled()) {
                    log.debug("[{}] [{}] Got ack for timed out msg {} last-seq: {}", topic, producerName, sequenceId,
                            expectedSequenceId);
                }
            } else {
                // Message was persisted correctly
                if (log.isDebugEnabled()) {
                    log.debug("[{}] [{}] Received ack for msg {} ", topic, producerName, sequenceId);
                }
                pendingMessages.remove();
                semaphore.release(op.numMessagesInBatch);
                callback = true;
                pendingCallbacks.add(op);
            }
        }
        if (callback) {
            op = pendingCallbacks.poll();
            if (op != null) {
                op.setMessageId(ledgerId, entryId, partitionIndex);
                try {
                    // Need to protect ourselves from any exception being thrown in the future handler from the
                    // application
                    op.callback.sendComplete(null);
                } catch (Throwable t) {
                    log.warn("[{}] [{}] Got exception while completing the callback for msg {}:", topic, producerName,
                            sequenceId, t);
                }
                ReferenceCountUtil.safeRelease(op.cmd);
                op.recycle();
            }
        }
    }

    private static final class OpSendMsg {
        MessageImpl msg;
        List<MessageImpl> msgs;
        ByteBuf cmd;
        SendCallback callback;
        long sequenceId;
        long createdAt;
        long batchSizeByte = 0;
        int numMessagesInBatch = 1;

        static OpSendMsg create(MessageImpl msg, ByteBuf cmd, long sequenceId, SendCallback callback) {
            OpSendMsg op = RECYCLER.get();
            op.msg = msg;
            op.cmd = cmd;
            op.callback = callback;
            op.sequenceId = sequenceId;
            op.createdAt = System.currentTimeMillis();
            return op;
        }

        static OpSendMsg create(List<MessageImpl> msgs, ByteBuf cmd, long sequenceId, SendCallback callback) {
            OpSendMsg op = RECYCLER.get();
            op.msgs = msgs;
            op.cmd = cmd;
            op.callback = callback;
            op.sequenceId = sequenceId;
            op.createdAt = System.currentTimeMillis();
            return op;
        }

        void recycle() {
            msg = null;
            msgs = null;
            cmd = null;
            callback = null;
            sequenceId = -1;
            createdAt = -1;
            RECYCLER.recycle(this, recyclerHandle);
        }

        void setNumMessagesInBatch(int numMessagesInBatch) {
            this.numMessagesInBatch = numMessagesInBatch;
        }

        void setBatchSizeByte(long batchSizeByte) {
            this.batchSizeByte = batchSizeByte;
        }

        void setMessageId(long ledgerId, long entryId, int partitionIndex) {
            if (msg != null) {
                msg.setMessageId(new MessageIdImpl(ledgerId, entryId, partitionIndex));
            } else {
                for (int batchIndex = 0; batchIndex < msgs.size(); batchIndex++) {
                    msgs.get(batchIndex)
                            .setMessageId(new BatchMessageIdImpl(ledgerId, entryId, partitionIndex, batchIndex));
                }
            }
        }

        private OpSendMsg(Handle recyclerHandle) {
            this.recyclerHandle = recyclerHandle;
        }

        private final Handle recyclerHandle;
        private static final Recycler<OpSendMsg> RECYCLER = new Recycler<OpSendMsg>() {
            @Override
            protected OpSendMsg newObject(Handle handle) {
                return new OpSendMsg(handle);
            }
        };
    }

    @Override
    void connectionOpened(final ClientCnx cnx) {
        // we set the cnx reference before registering the producer on the cnx, so if the cnx breaks before creating the
        // producer, it will try to grab a new cnx
        clientCnx.set(cnx);
        cnx.registerProducer(producerId, this);

        log.info("[{}] [{}] Creating producer on cnx {}", topic, producerName, cnx.ctx().channel());

        long requestId = client.newRequestId();

        cnx.sendRequestWithId(Commands.newProducer(topic, producerId, requestId, producerName), requestId)
                .thenAccept(producerName -> {
                    // We are now reconnected to broker and clear to send messages. Re-send all pending messages and
                    // set the cnx pointer so that new messages will be sent immediately
                    synchronized (ProducerImpl.this) {
                        if (state.get() == State.Closing || state.get() == State.Closed) {
                            // Producer was closed while reconnecting, close the connection to make sure the broker
                            // drops the producer on its side
                            cnx.removeProducer(producerId);
                            cnx.channel().close();
                            return;
                        }
                        resetBackoff();

                        log.info("[{}] [{}] Created producer on cnx {}", topic, producerName, cnx.ctx().channel());
                        connectionId = cnx.ctx().channel().toString();
                        connectedSince = DATE_FORMAT.format(new Date(System.currentTimeMillis()));

                        if (this.producerName == null) {
                            this.producerName = producerName;
                        }

                        if (!producerCreatedFuture.isDone() && isBatchMessagingEnabled()) {
                            // schedule the first batch message task
                            client.timer().newTimeout(batchMessageAndSendTask, conf.getBatchingMaxPublishDelayMs(),
                                    TimeUnit.MILLISECONDS);
                        }
                        resendMessages(cnx);
                    }
                }).exceptionally((e) -> {
                    cnx.removeProducer(producerId);
                    if (state.get() == State.Closing || state.get() == State.Closed) {
                        // Producer was closed while reconnecting, close the connection to make sure the broker
                        // drops the producer on its side
                        cnx.channel().close();
                        return null;
                    }
                    log.error("[{}] [{}] Failed to create producer: {}", topic, producerName,
                            e.getCause().getMessage());

                    if (e.getCause() instanceof PulsarClientException.ProducerBlockedQuotaExceededException) {
                        synchronized (this) {
                            log.warn("[{}] [{}] Topic backlog quota exceeded. Throwing Exception on producer.", topic,
                                    producerName);

                            if (log.isDebugEnabled()) {
                                log.debug("[{}] [{}] Pending messages: {}", topic, producerName,
                                        pendingMessages.size());
                            }

                            PulsarClientException bqe = new PulsarClientException.ProducerBlockedQuotaExceededException(
                                    "Could not send pending messages as backlog exceeded");
                            failPendingMessages(cnx(), bqe);
                        }
                    } else if (e.getCause() instanceof PulsarClientException.ProducerBlockedQuotaExceededError) {
                        log.warn("[{}] [{}] Producer is blocked on creation because backlog exceeded on topic.",
                                producerName, topic);
                    }

                    if (producerCreatedFuture.isDone() || //
                    (e.getCause() instanceof PulsarClientException
                            && isRetriableError((PulsarClientException) e.getCause())
                            && System.currentTimeMillis() < createProducerTimeout)) {
                        // Either we had already created the producer once (producerCreatedFuture.isDone()) or we are
                        // still within the initial timeout budget and we are dealing with a retriable error
                        reconnectLater(e.getCause());
                    } else {
                        state.set(State.Failed);
                        producerCreatedFuture.completeExceptionally(e.getCause());
                        client.cleanupProducer(this);
                    }

                    return null;
                });
    }

    @Override
    void connectionFailed(PulsarClientException exception) {
        if (System.currentTimeMillis() > createProducerTimeout
                && producerCreatedFuture.completeExceptionally(exception)) {
            log.info("[{}] Producer creation failed for producer {}", topic, producerId);
            state.set(State.Failed);
        }
    }

    private void resendMessages(ClientCnx cnx) {
        cnx.ctx().channel().eventLoop().execute(() -> {
            synchronized (this) {
                if (state.get() == State.Closing || state.get() == State.Closed) {
                    // Producer was closed while reconnecting, close the connection to make sure the broker
                    // drops the producer on its side
                    cnx.channel().close();
                    return;
                }
                int messagesToResend = pendingMessages.size();
                if (messagesToResend == 0) {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] [{}] No pending messages to resend {}", topic, producerName, messagesToResend);
                    }
                    if (changeToReadyState()) {
                        producerCreatedFuture.complete(ProducerImpl.this);
                        return;
                    } else {
                        // Producer was closed while reconnecting, close the connection to make sure the broker
                        // drops the producer on its side
                        cnx.channel().close();
                        return;
                    }

                }

                log.info("[{}] [{}] Re-Sending {} messages to server", topic, producerName, messagesToResend);

                for (OpSendMsg op : pendingMessages) {
                    op.cmd.retain();
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] [{}] Re-Sending message in cnx {}, sequenceId {}", topic, producerName,
                                cnx.channel(), op.sequenceId);
                    }
                    cnx.ctx().write(op.cmd, cnx.ctx().voidPromise());
                    stats.updateNumMsgsSent(op.numMessagesInBatch, op.batchSizeByte);
                }

                cnx.ctx().flush();
                if (!changeToReadyState()) {
                    // Producer was closed while reconnecting, close the connection to make sure the broker
                    // drops the producer on its side
                    cnx.channel().close();
                    return;
                }
            }
        });
    }

    @Override
    String getHandlerName() {
        return producerName;
    }

    /**
     * Process sendTimeout events
     */
    @Override
    public void run(Timeout timeout) throws Exception {
        if (timeout.isCancelled()) {
            return;
        }

        long timeToWaitMs;

        synchronized (this) {
            OpSendMsg firstMsg = pendingMessages.peek();
            if (firstMsg == null) {
                // If there are no pending messages, reset the timeout to the configured value.
                timeToWaitMs = conf.getSendTimeoutMs();
            } else {
                // If there is at least one message, calculate the diff between the message timeout and the current
                // time.
                long diff = (firstMsg.createdAt + conf.getSendTimeoutMs()) - System.currentTimeMillis();
                if (diff <= 0) {
                    // The diff is less than or equal to zero, meaning that the message has been timed out.
                    // Set the callback to timeout on every message, then clear the pending queue.
                    log.info("[{}] [{}] Message send timed out. Failing {} messages", topic, producerName,
                            pendingMessages.size());

                    PulsarClientException te = new PulsarClientException.TimeoutException(
                            "Could not send message to broker within given timeout");
                    failPendingMessages(cnx(), te);
                    stats.incrementSendFailed(pendingMessages.size());
                    // Since the pending queue is cleared now, set timer to expire after configured value.
                    timeToWaitMs = conf.getSendTimeoutMs();
                } else {
                    // The diff is greater than zero, set the timeout to the diff value
                    timeToWaitMs = diff;
                }
            }
        }

        sendTimeout = client.timer().newTimeout(this, timeToWaitMs, TimeUnit.MILLISECONDS);
    }

    /**
     * This fails and clears the pending messages with the given exception. This method should be called from within the
     * ProducerImpl object mutex.
     */
    private void failPendingMessages(ClientCnx cnx, PulsarClientException ex) {
        if (cnx == null) {
            final AtomicInteger releaseCount = new AtomicInteger();
            pendingMessages.forEach(op -> {
                releaseCount.addAndGet(op.numMessagesInBatch);
                try {
                    // Need to protect ourselves from any exception being thrown in the future handler from the
                    // application
                    op.callback.sendComplete(ex);
                } catch (Throwable t) {
                    log.warn("[{}] [{}] Got exception while completing the callback for msg {}:", topic, producerName,
                            op.sequenceId, t);
                }
                ReferenceCountUtil.safeRelease(op.cmd);
            });
            semaphore.release(releaseCount.get());
            pendingMessages.clear();
            pendingCallbacks.clear();
            if (isBatchMessagingEnabled()) {
                failPendingBatchMessages(ex);
            }
        } else {
            // If we have a connection, we schedule the callback and recycle on the event loop thread to avoid any
            // race condition since we also write the message on the socket from this thread
            cnx.ctx().channel().eventLoop().execute(() -> {
                synchronized (ProducerImpl.this) {
                    failPendingMessages(null, ex);
                }
            });
        }
    }

    /**
     * fail any pending batch messages that were enqueued, however batch was not closed out
     *
     */
    private void failPendingBatchMessages(PulsarClientException ex) {
        if (batchMessageContainer.isEmpty()) {
            return;
        }
        int numMessagesInBatch = batchMessageContainer.numMessagesInBatch;
        semaphore.release(numMessagesInBatch);
        try {
            // Need to protect ourselves from any exception being thrown in the future handler from the application
            batchMessageContainer.firstCallback.sendComplete(ex);
        } catch (Throwable t) {
            log.warn("[{}] [{}] Got exception while completing the callback for msg {}:", topic, producerName,
                    batchMessageContainer.sequenceId, t);
        }
        ReferenceCountUtil.safeRelease(batchMessageContainer.getBatchedSingleMessageMetadataAndPayload());
        batchMessageContainer.clear();
    }

    TimerTask batchMessageAndSendTask = new TimerTask() {

        @Override
        public void run(Timeout timeout) throws Exception {
            if (timeout.isCancelled()) {
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] Batching the messages from the batch container from timer thread", topic,
                        producerName);
            }
            // semaphore acquired when message was enqueued to container
            synchronized (ProducerImpl.this) {
                batchMessageAndSend();
            }
            // schedule the next batch message task
            client.timer().newTimeout(this, conf.getBatchingMaxPublishDelayMs(), TimeUnit.MILLISECONDS);
        }
    };

    // must acquire semaphore before enqueuing
    private void batchMessageAndSend() {
        if (log.isDebugEnabled()) {
            log.debug("[{}] [{}] Batching the messages from the batch container with {} messages", topic, producerName,
                    batchMessageContainer.numMessagesInBatch);
        }
        OpSendMsg op = null;
        int numMessagesInBatch = 0;
        try {
            if (!batchMessageContainer.isEmpty()) {
                numMessagesInBatch = batchMessageContainer.numMessagesInBatch;
                // checksum is on uncompressed payload for batch
                batchMessageContainer.setChecksum();
                ByteBuf compressedPayload = batchMessageContainer.getCompressedBatchMetadataAndPayload();
                long sequenceId = batchMessageContainer.sequenceId;
                ByteBuf cmd = Commands.newSend(producerId, sequenceId, batchMessageContainer.numMessagesInBatch,
                        batchMessageContainer.setBatchAndBuild(), compressedPayload);

                op = OpSendMsg.create(batchMessageContainer.messages, cmd, sequenceId,
                        batchMessageContainer.firstCallback);

                op.setNumMessagesInBatch(batchMessageContainer.numMessagesInBatch);
                op.setBatchSizeByte(batchMessageContainer.currentBatchSizeBytes);

                batchMessageContainer.clear();

                pendingMessages.put(op);

                if (isConnected()) {
                    // If we do have a connection, the message is sent immediately, otherwise we'll try again once a new
                    // connection is established
                    cmd.retain();
                    cnx().ctx().channel().eventLoop().execute(WriteInEventLoopCallback.create(this, cnx(), op));
                    stats.updateNumMsgsSent(numMessagesInBatch, op.batchSizeByte);
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] [{}] Connection is not ready -- sequenceId {}", topic, producerName,
                                sequenceId);
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            semaphore.release(numMessagesInBatch);
            if (op != null) {
                op.callback.sendComplete(new PulsarClientException(ie));
            }
        } catch (Throwable t) {
            semaphore.release(numMessagesInBatch);
            log.warn("[{}] [{}] error while closing out batch -- {}", topic, producerName, t);
            if (op != null) {
                op.callback.sendComplete(new PulsarClientException(t));
            }
        }
    }

    public long getDelayInMillis() {
        OpSendMsg firstMsg = pendingMessages.peek();
        if (firstMsg != null) {
            return System.currentTimeMillis() - firstMsg.createdAt;
        }
        return 0L;
    }

    public String getConnectionId() {
        return cnx() != null ? connectionId : null;
    }

    public String getConnectedSince() {
        return cnx() != null ? connectedSince : null;
    }

    public int getPendingQueueSize() {
        return pendingMessages.size();
    }

    private static SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private PulsarApi.CompressionType convertCompressionType(CompressionType compressionType) {
        switch (compressionType) {
        case NONE:
            return PulsarApi.CompressionType.NONE;
        case LZ4:
            return PulsarApi.CompressionType.LZ4;
        case ZLIB:
            return PulsarApi.CompressionType.ZLIB;

        default:
            throw new RuntimeException("Invalid compression type");
        }
    }

    @Override
    public ProducerStats getStats() {
        if (stats instanceof ProducerStatsDisabled) {
            return null;
        }
        return stats;
    }

    public String getProducerName() {
        return producerName;
    }

    private static final Logger log = LoggerFactory.getLogger(ProducerImpl.class);
}
