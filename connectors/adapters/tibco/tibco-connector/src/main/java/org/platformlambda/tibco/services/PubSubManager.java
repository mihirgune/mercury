/*

    Copyright 2018-2024 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.tibco.services;

import org.platformlambda.cloud.ConnectorConfig;
import org.platformlambda.cloud.EventProducer;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.models.PubSubProvider;
import org.platformlambda.core.serializers.SimpleMapper;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.EventEmitter;
import org.platformlambda.tibco.TibcoConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class PubSubManager implements PubSubProvider {
    private static final Logger log = LoggerFactory.getLogger(PubSubManager.class);

    private static final String PUBLISHER = "event.publisher";
    private static final String TYPE = "type";
    private static final String PARTITIONS = "partitions";
    private static final String PARTITION = "partition";
    private static final String BODY = "body";
    private static final String CREATE = "create";
    private static final String LIST = "list";
    private static final String EXISTS = "exists";
    private static final String DELETE = "delete";
    private static final String TOPIC = "topic";
    private static final String QUEUE = "queue";
    private static final ConcurrentMap<String, EventConsumer> subscribers = new ConcurrentHashMap<>();
    private final String domain;
    private final Properties properties;
    private final String cloudManager;
    private final Map<String, String> preAllocatedTopics;

    @SuppressWarnings("unchecked")
    public PubSubManager(String domain, Properties properties, String cloudManager)
            throws JMSException, IOException {
        this.domain = domain;
        this.properties = properties;
        this.cloudManager = cloudManager;
        this.preAllocatedTopics = ConnectorConfig.getTopicSubstitution();

        LambdaFunction publisher = (headers, input, instance) -> {
            if (input instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) input;
                Object topic = data.get(TOPIC);
                Object partition = data.get(PARTITION);
                Object payload = data.get(BODY);
                if (topic instanceof String && partition instanceof Integer) {
                    sendEvent((String) topic, (int) partition, headers, payload);
                }
            }
            return true;
        };
        Platform platform = Platform.getInstance();
        try {
            // start Topic Manager
            log.info("Starting {} pub/sub manager - {}", domain, cloudManager);
            platform.registerPrivate(cloudManager, new TopicManager(domain, properties), 1);
            // start publisher
            platform.registerPrivate(PUBLISHER, publisher, 1);
        } catch (IOException e) {
            log.error("Unable to start - {}", e.getMessage());
        }
        // clean up subscribers when application stops
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    @Override
    public boolean createTopic(String topic) throws IOException {
        return createTopicOrQueue(true, topic, -1);
    }

    @Override
    public boolean createTopic(String topic, int partitions) throws IOException {
        return createTopicOrQueue(true, topic, partitions);
    }

    private boolean createTopicOrQueue(boolean isTopic, String topicOrQueue, int partitions) throws IOException {
        ConnectorConfig.validateTopicName(topicOrQueue);
        final long timeout = 20 * 1000L;
        final BlockingQueue<EventEnvelope> bench = new ArrayBlockingQueue<>(1);
        EventEnvelope req = new EventEnvelope().setTo(cloudManager).setHeader(TYPE, CREATE)
                .setHeader(isTopic? TOPIC : QUEUE, topicOrQueue).setHeader(PARTITIONS, partitions);
        try {
            EventEmitter.getInstance().asyncRequest(req, timeout).onSuccess(bench::offer);
            EventEnvelope res = bench.poll(timeout, TimeUnit.MILLISECONDS);
            if (res != null && res.getBody() instanceof Boolean) {
                return (Boolean) res.getBody();
            } else {
                return false;
            }
        } catch (InterruptedException e) {
            return false;
        }
    }

    @Override
    public void deleteTopic(String topic) throws IOException {
        final long timeout = 20 * 1000L;
        final BlockingQueue<EventEnvelope> bench = new ArrayBlockingQueue<>(1);
        EventEnvelope req = new EventEnvelope().setTo(cloudManager).setHeader(TYPE, DELETE).setHeader(TOPIC, topic);
        try {
            EventEmitter.getInstance().asyncRequest(req, timeout).onSuccess(bench::offer);
            bench.poll(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // ok to ignore
        }
    }

    @Override
    public boolean createQueue(String queue) throws IOException {
        return createTopicOrQueue(false, queue, -1);
    }

    @Override
    public void deleteQueue(String queue) throws IOException {
        final long timeout = 20 * 1000L;
        final BlockingQueue<EventEnvelope> bench = new ArrayBlockingQueue<>(1);
        EventEnvelope req = new EventEnvelope().setTo(cloudManager).setHeader(TYPE, DELETE).setHeader(QUEUE, queue);
        try {
            EventEmitter.getInstance().asyncRequest(req, timeout).onSuccess(bench::offer);
            bench.poll(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // ok to ignore
        }
    }

    @Override
    public void publish(String topic, Map<String, String> headers, Object body) throws IOException {
        publish(topic, -1, headers, body);
    }

    @Override
    public void publish(String topic, int partition, Map<String, String> headers, Object body) throws IOException {
        ConnectorConfig.validateTopicName(topic);
        Map<String, String> eventHeaders = headers == null? new HashMap<>() : headers;
        if (eventHeaders.containsKey(EventProducer.EMBED_EVENT) && body instanceof byte[]) {
            // embedded events are sent by the EventPublisher thread
            sendEvent(topic, partition, eventHeaders, body);
        } else {
            final Object payload;
            if (body instanceof byte[]) {
                payload = body;
                eventHeaders.put(EventProducer.DATA_TYPE, EventProducer.BYTES_DATA);
            } else if (body instanceof String) {
                payload = body;
                eventHeaders.put(EventProducer.DATA_TYPE, EventProducer.TEXT_DATA);
            } else if (body instanceof Map) {
                payload = SimpleMapper.getInstance().getMapper().writeValueAsString(body);
                eventHeaders.put(EventProducer.DATA_TYPE, EventProducer.MAP_DATA);
            } else if (body instanceof List) {
                payload = SimpleMapper.getInstance().getMapper().writeValueAsString(body);
                eventHeaders.put(EventProducer.DATA_TYPE, EventProducer.LIST_DATA);
            } else {
                // other primitive and PoJo are serialized as JSON string
                payload = SimpleMapper.getInstance().getMapper().writeValueAsString(body);
                eventHeaders.put(EventProducer.DATA_TYPE, EventProducer.TEXT_DATA);
            }
            /*
             * for thread safety, tell the singleton publisher to send event
             */
            Map<String, Object> data = new HashMap<>();
            data.put(TOPIC, topic);
            data.put(PARTITION, partition);
            data.put(BODY, payload);
            EventEnvelope event = new EventEnvelope();
            event.setHeaders(eventHeaders).setBody(data).setTo(PUBLISHER);
            EventEmitter.getInstance().send(event);
        }
    }

    private void sendEvent(String topic, int partition, Map<String, String> headers, Object body) {
        String realTopic = partition < 0 ? topic : topic + "." + partition;
        if (ConnectorConfig.topicSubstitutionEnabled()) {
            realTopic = preAllocatedTopics.getOrDefault(realTopic, realTopic);
        }
        try {
            Connection connection = TibcoConnector.getConnection(domain, properties);
            try (Session session = connection.createSession(Session.AUTO_ACKNOWLEDGE);
                 MessageProducer producer = session.createProducer(partition == -2?
                         session.createQueue(realTopic) : session.createTopic(realTopic))) {
                if (body instanceof byte[]) {
                    BytesMessage message = session.createBytesMessage();
                    for (String h : headers.keySet()) {
                        message.setStringProperty(h, headers.get(h));
                    }
                    message.writeBytes((byte[]) body);
                    producer.send(message);

                } else if (body instanceof String) {
                    TextMessage message = session.createTextMessage((String) body);
                    for (String h : headers.keySet()) {
                        message.setStringProperty(h, headers.get(h));
                    }
                    producer.send(message);

                } else {
                    log.error("Event to {} not published because it is not Text or Binary", realTopic);
                }
            }
        } catch (Exception e) {
            log.error("Unable to publish event to {} - {}", realTopic, e.getMessage());
            // just let the platform such as Kubernetes to restart the application instance
            System.exit(12);
        }
    }

    @Override
    public void subscribe(String topic, LambdaFunction listener, String... parameters) throws IOException {
        subscribe(topic, -1, listener, parameters);
    }

    @Override
    public void subscribe(String topic, int partition, LambdaFunction listener, String... parameters) throws IOException {
        ConnectorConfig.validateTopicName(topic);
        String topicPartition = (topic + (partition < 0? "" : "." + partition)).toLowerCase();
        if (subscribers.containsKey(topicPartition) || Platform.getInstance().hasRoute(topicPartition)) {
            throw new IOException(topicPartition+" is already subscribed");
        }
        EventConsumer consumer = new EventConsumer(domain, properties, topic, partition, parameters);
        consumer.start();
        Platform.getInstance().registerPrivate(topicPartition, listener, 1);
        subscribers.put(topicPartition, consumer);
    }

    @Override
    public void unsubscribe(String topic) throws IOException {
        unsubscribe(topic, -1);
    }

    @Override
    public void unsubscribe(String topic, int partition) throws IOException {
        String topicPartition = (topic + (partition < 0? "" : "." + partition)).toLowerCase();
        Platform platform = Platform.getInstance();
        if (platform.hasRoute(topicPartition) && subscribers.containsKey(topicPartition)) {
            EventConsumer consumer = subscribers.get(topicPartition);
            platform.release(topicPartition);
            subscribers.remove(topicPartition);
            consumer.shutdown();
        } else {
            if (partition > -1) {
                throw new IOException(topicPartition +
                        " has not been subscribed by this application instance");
            } else {
                throw new IOException(topic + " has not been subscribed by this application instance");
            }
        }
    }

    @Override
    public void send(String queue, Map<String, String> headers, Object body) throws IOException {
        // partition of "-2" is encoded as "queue"
        publish(queue, -2, headers, body);
    }

    @Override
    public void listen(String queue, LambdaFunction listener, String... parameters) throws IOException {
        // partition of "-2" is encoded as "queue"
        subscribe(queue, -2, listener, parameters);
    }

    @Override
    public boolean exists(String topic) throws IOException {
        final long timeout = 20 * 1000L;
        final BlockingQueue<EventEnvelope> bench = new ArrayBlockingQueue<>(1);
        EventEnvelope req = new EventEnvelope().setTo(cloudManager).setHeader(TYPE, EXISTS).setHeader(TOPIC, topic);
        try {
            EventEmitter.getInstance().asyncRequest(req, timeout).onSuccess(bench::offer);
            EventEnvelope res = bench.poll(timeout, TimeUnit.MILLISECONDS);
            if (res != null && res.getBody() instanceof Boolean) {
                return (Boolean) res.getBody();
            } else {
                return false;
            }
        } catch (InterruptedException e) {
            return false;
        }
    }

    @Override
    public int partitionCount(String topic) throws IOException {
        final long timeout = 20 * 1000L;
        final BlockingQueue<EventEnvelope> bench = new ArrayBlockingQueue<>(1);
        EventEnvelope req = new EventEnvelope().setTo(cloudManager).setHeader(TYPE, PARTITIONS).setHeader(TOPIC, topic);
        try {
            EventEmitter.getInstance().asyncRequest(req, timeout).onSuccess(bench::offer);
            EventEnvelope res = bench.poll(timeout, TimeUnit.MILLISECONDS);
            if (res != null && res.getBody() instanceof Integer) {
                return (Integer) res.getBody();
            } else {
                return -1;
            }
        } catch (InterruptedException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<String> list() throws IOException {
        long timeout = 20 * 1000L;
        final BlockingQueue<EventEnvelope> bench = new ArrayBlockingQueue<>(1);
        EventEnvelope req = new EventEnvelope().setTo(cloudManager).setHeader(TYPE, LIST);
        try {
            EventEmitter.getInstance().asyncRequest(req, timeout).onSuccess(bench::offer);
            EventEnvelope res = bench.poll(timeout, TimeUnit.MILLISECONDS);
            if (res != null && res.getBody() instanceof List) {
                return (List<String>) res.getBody();
            } else {
                return Collections.emptyList();
            }
        } catch (InterruptedException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public boolean isStreamingPubSub() {
        return false;
    }

    @Override
    public void cleanup() {
        // no-op
    }

    private void shutdown() {
        for (String topic: subscribers.keySet()) {
            EventConsumer consumer = subscribers.get(topic);
            consumer.shutdown();
        }
    }

}
