// Copyright 2020 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client.impl;

import io.nats.client.*;
import io.nats.client.api.*;
import io.nats.client.support.RandomUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static io.nats.client.support.NatsConstants.EMPTY;
import static io.nats.client.support.NatsJetStreamClientError.*;
import static org.junit.jupiter.api.Assertions.*;

public class JetStreamGeneralTests extends JetStreamTestBase {

    @Test
    public void testJetStreamContextCreate() throws Exception {
        runInJsServer(nc -> {
            createDefaultTestStream(nc); // tries management functions
            nc.jetStreamManagement().getAccountStatistics(); // another management
            nc.jetStream().publish(SUBJECT, dataBytes(1));
        });
    }

    @Test
    public void testJetNotEnabled() throws Exception {
        runInServer(nc -> {
            // get normal context, try to do an operation
            JetStream js = nc.jetStream();
            assertThrows(IOException.class, () -> js.subscribe(SUBJECT));

            // get management context, try to do an operation
            JetStreamManagement jsm = nc.jetStreamManagement();
            assertThrows(IOException.class, jsm::getAccountStatistics);
        });
    }

    @Test
    public void testJetEnabledGoodAccount() throws Exception {
        try (NatsTestServer ts = new NatsTestServer("src/test/resources/js_authorization.conf", false, true)) {
            Options options = new Options.Builder().server(ts.getURI())
                    .userInfo("serviceup".toCharArray(), "uppass".toCharArray()).build();
            Connection nc = standardConnection(options);
            nc.jetStreamManagement();
            nc.jetStream();
        }
    }

    @Test
    public void testJetStreamPublishDefaultOptions() throws Exception {
        runInJsServer(nc -> {
            createDefaultTestStream(nc);
            JetStream js = nc.jetStream();
            PublishAck ack = jsPublish(js);
            assertEquals(1, ack.getSeqno());
        });
    }

    @Test
    public void testConnectionClosing() throws Exception {
        runInJsServer(nc -> {
            nc.close();
            assertThrows(IOException.class, nc::jetStream);
            assertThrows(IOException.class, nc::jetStreamManagement);
        });
    }

    @Test
    public void testCreateWithOptionsForCoverage() throws Exception {
        runInJsServer(nc -> {
            JetStreamOptions jso = JetStreamOptions.builder().build();
            nc.jetStream(jso);
            nc.jetStreamManagement(jso);
        });
    }

    @Test
    public void testMiscMetaDataCoverage() {
        Message jsMsg = getTestJsMessage();
        assertTrue(jsMsg.isJetStream());

        // two calls to msg.metaData are for coverage to test lazy initializer
        assertNotNull(jsMsg.metaData()); // this call takes a different path
        assertNotNull(jsMsg.metaData()); // this call shows that the lazy will work
    }

    @Test
    public void testJetStreamSubscribe() throws Exception {
        runInJsServer(nc -> {
            JetStream js = nc.jetStream();
            JetStreamManagement jsm = nc.jetStreamManagement();

            createDefaultTestStream(jsm);
            jsPublish(js);

            // default ephemeral subscription.
            Subscription s = js.subscribe(SUBJECT);
            Message m = s.nextMessage(DEFAULT_TIMEOUT);
            assertNotNull(m);
            assertEquals(DATA, new String(m.getData()));
            List<String> names = jsm.getConsumerNames(STREAM);
            assertEquals(1, names.size());

            // default subscribe options // ephemeral subscription.
            s = js.subscribe(SUBJECT, PushSubscribeOptions.builder().build());
            m = s.nextMessage(DEFAULT_TIMEOUT);
            assertNotNull(m);
            assertEquals(DATA, new String(m.getData()));
            names = jsm.getConsumerNames(STREAM);
            assertEquals(2, names.size());

            // set the stream
            PushSubscribeOptions pso = PushSubscribeOptions.builder().stream(STREAM).durable(DURABLE).build();
            s = js.subscribe(SUBJECT, pso);
            m = s.nextMessage(DEFAULT_TIMEOUT);
            assertNotNull(m);
            assertEquals(DATA, new String(m.getData()));
            names = jsm.getConsumerNames(STREAM);
            assertEquals(3, names.size());

            // coverage
            Dispatcher dispatcher = nc.createDispatcher();
            js.subscribe(SUBJECT);
            js.subscribe(SUBJECT, (PushSubscribeOptions)null);
            js.subscribe(SUBJECT, QUEUE, null);
            js.subscribe(SUBJECT, dispatcher, mh -> {}, false);
            js.subscribe(SUBJECT, dispatcher, mh -> {}, false, null);
            js.subscribe(SUBJECT, QUEUE, dispatcher, mh -> {}, false, null);

            // bind with w/o subject
            jsm.addOrUpdateConsumer(STREAM,
                ConsumerConfiguration.builder()
                    .durable(durable(101))
                    .deliverSubject(deliver(101))
                    .build());

            PushSubscribeOptions psoBind = PushSubscribeOptions.bind(STREAM, durable(101));
            js.subscribe(null, psoBind).unsubscribe();
            js.subscribe("", psoBind).unsubscribe();
            JetStreamSubscription sub = js.subscribe(null, dispatcher, mh -> {}, false, psoBind);
            dispatcher.unsubscribe(sub);
            js.subscribe("", dispatcher, mh -> {}, false, psoBind);

            jsm.addOrUpdateConsumer(STREAM,
                ConsumerConfiguration.builder()
                    .durable(durable(102))
                    .deliverSubject(deliver(102))
                    .deliverGroup(queue(102))
                    .build());

            psoBind = PushSubscribeOptions.bind(STREAM, durable(102));
            js.subscribe(null, queue(102), psoBind).unsubscribe();
            js.subscribe("", queue(102), psoBind).unsubscribe();
            sub = js.subscribe(null, queue(102), dispatcher, mh -> {}, false, psoBind);
            dispatcher.unsubscribe(sub);
            js.subscribe("", queue(102), dispatcher, mh -> {}, false, psoBind);
        });
    }

    @Test
    public void testJetStreamSubscribeErrors() throws Exception {
        runInJsServer(nc -> {
            JetStream js = nc.jetStream();

            // stream not found
            PushSubscribeOptions psoInvalidStream = PushSubscribeOptions.builder().stream(STREAM).build();
            assertThrows(JetStreamApiException.class, () -> js.subscribe(SUBJECT, psoInvalidStream));

            // subject
            IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(null));
            assertTrue(iae.getMessage().startsWith("Subject"));
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(HAS_SPACE));
            assertTrue(iae.getMessage().startsWith("Subject"));
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(null, (PushSubscribeOptions)null));
            assertTrue(iae.getMessage().startsWith("Subject"));

            // queue
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, HAS_SPACE, null));
            assertTrue(iae.getMessage().startsWith("Queue"));
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, HAS_SPACE, null, null, false, null));
            assertTrue(iae.getMessage().startsWith("Queue"));

            // dispatcher
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, null, null, false));
            assertTrue(iae.getMessage().startsWith("Dispatcher"));
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, null, null, false, null));
            assertTrue(iae.getMessage().startsWith("Dispatcher"));
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, QUEUE, null, null, false, null));
            assertTrue(iae.getMessage().startsWith("Dispatcher"));

            // handler
            Dispatcher dispatcher = nc.createDispatcher();
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, dispatcher, null, false));
            assertTrue(iae.getMessage().startsWith("Handler"));
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, dispatcher, null, false, null));
            assertTrue(iae.getMessage().startsWith("Handler"));
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, QUEUE, dispatcher, null, false, null));
            assertTrue(iae.getMessage().startsWith("Handler"));
        });
    }

    @Test
    public void testFilterSubjectEphemeral() throws Exception {
        runInJsServer(nc -> {
            // Create our JetStream context.
            JetStream js = nc.jetStream();

            String subjectWild = SUBJECT + ".*";
            String subjectA = SUBJECT + ".A";
            String subjectB = SUBJECT + ".B";

            // create the stream.
            createMemoryStream(nc, STREAM, subjectWild);

            jsPublish(js, subjectA, 1);
            jsPublish(js, subjectB, 1);
            jsPublish(js, subjectA, 1);
            jsPublish(js, subjectB, 1);

            // subscribe to the wildcard
            ConsumerConfiguration cc = ConsumerConfiguration.builder().ackPolicy(AckPolicy.None).build();
            PushSubscribeOptions pso = PushSubscribeOptions.builder().configuration(cc).build();
            JetStreamSubscription sub = js.subscribe(subjectWild, pso);
            nc.flush(Duration.ofSeconds(1));

            Message m = sub.nextMessage(Duration.ofSeconds(1));
            assertEquals(subjectA, m.getSubject());
            assertEquals(1, m.metaData().streamSequence());
            m = sub.nextMessage(Duration.ofSeconds(1));
            assertEquals(subjectB, m.getSubject());
            assertEquals(2, m.metaData().streamSequence());
            m = sub.nextMessage(Duration.ofSeconds(1));
            assertEquals(subjectA, m.getSubject());
            assertEquals(3, m.metaData().streamSequence());
            m = sub.nextMessage(Duration.ofSeconds(1));
            assertEquals(subjectB, m.getSubject());
            assertEquals(4, m.metaData().streamSequence());

            // subscribe to A
            cc = ConsumerConfiguration.builder().filterSubject(subjectA).ackPolicy(AckPolicy.None).build();
            pso = PushSubscribeOptions.builder().configuration(cc).build();
            sub = js.subscribe(subjectWild, pso);
            nc.flush(Duration.ofSeconds(1));

            m = sub.nextMessage(Duration.ofSeconds(1));
            assertEquals(subjectA, m.getSubject());
            assertEquals(1, m.metaData().streamSequence());
            m = sub.nextMessage(Duration.ofSeconds(1));
            assertEquals(subjectA, m.getSubject());
            assertEquals(3, m.metaData().streamSequence());
            m = sub.nextMessage(Duration.ofSeconds(1));
            assertNull(m);

            // subscribe to B
            cc = ConsumerConfiguration.builder().filterSubject(subjectB).ackPolicy(AckPolicy.None).build();
            pso = PushSubscribeOptions.builder().configuration(cc).build();
            sub = js.subscribe(subjectWild, pso);
            nc.flush(Duration.ofSeconds(1));

            m = sub.nextMessage(Duration.ofSeconds(1));
            assertEquals(subjectB, m.getSubject());
            assertEquals(2, m.metaData().streamSequence());
            m = sub.nextMessage(Duration.ofSeconds(1));
            assertEquals(subjectB, m.getSubject());
            assertEquals(4, m.metaData().streamSequence());
            m = sub.nextMessage(Duration.ofSeconds(1));
            assertNull(m);
        });
    }

    @Test
    public void testPrefix() throws Exception {
        String prefix = "tar.api";
        String streamMadeBySrc = "stream-made-by-src";
        String streamMadeByTar = "stream-made-by-tar";
        String subjectMadeBySrc = "sub-made-by.src";
        String subjectMadeByTar = "sub-made-by.tar";

        try (NatsTestServer ts = new NatsTestServer("src/test/resources/js_prefix.conf", false)) {
            Options optionsSrc = new Options.Builder().server(ts.getURI())
                    .userInfo("src".toCharArray(), "spass".toCharArray()).build();

            Options optionsTar = new Options.Builder().server(ts.getURI())
                    .userInfo("tar".toCharArray(), "tpass".toCharArray()).build();

            try (Connection ncSrc = Nats.connect(optionsSrc);
                 Connection ncTar = Nats.connect(optionsTar)
            ) {
                // Setup JetStreamOptions. SOURCE does not need prefix
                JetStreamOptions jsoSrc = JetStreamOptions.builder().build();
                JetStreamOptions jsoTar = JetStreamOptions.builder().prefix(prefix).build();

                // Management api allows us to create streams
                JetStreamManagement jsmSrc = ncSrc.jetStreamManagement(jsoSrc);
                JetStreamManagement jsmTar = ncTar.jetStreamManagement(jsoTar);

                // add streams with both account
                StreamConfiguration scSrc = StreamConfiguration.builder()
                        .name(streamMadeBySrc)
                        .storageType(StorageType.Memory)
                        .subjects(subjectMadeBySrc)
                        .build();
                jsmSrc.addStream(scSrc);

                StreamConfiguration scTar = StreamConfiguration.builder()
                        .name(streamMadeByTar)
                        .storageType(StorageType.Memory)
                        .subjects(subjectMadeByTar)
                        .build();
                jsmTar.addStream(scTar);

                JetStream jsSrc = ncSrc.jetStream(jsoSrc);
                JetStream jsTar = ncTar.jetStream(jsoTar);

                jsSrc.publish(subjectMadeBySrc, "src-src".getBytes());
                jsSrc.publish(subjectMadeByTar, "src-tar".getBytes());
                jsTar.publish(subjectMadeBySrc, "tar-src".getBytes());
                jsTar.publish(subjectMadeByTar, "tar-tar".getBytes());

                // subscribe and read messages
                readPrefixMessages(ncSrc, jsSrc, subjectMadeBySrc, "src");
                readPrefixMessages(ncSrc, jsSrc, subjectMadeByTar, "tar");
                readPrefixMessages(ncTar, jsTar, subjectMadeBySrc, "src");
                readPrefixMessages(ncTar, jsTar, subjectMadeByTar, "tar");
            }
        }
    }

    private void readPrefixMessages(Connection nc, JetStream js, String subject, String dest) throws InterruptedException, IOException, JetStreamApiException, TimeoutException {
        JetStreamSubscription sub = js.subscribe(subject);
        nc.flush(Duration.ofSeconds(1));
        List<Message> msgs = readMessagesAck(sub);
        assertEquals(2, msgs.size());
        assertEquals(subject, msgs.get(0).getSubject());
        assertEquals(subject, msgs.get(1).getSubject());

        assertEquals("src-" + dest, new String(msgs.get(0).getData()));
        assertEquals("tar-" + dest, new String(msgs.get(1).getData()));
    }

    @Test
    public void testBindPush() throws Exception {
        runInJsServer(nc -> {
            createDefaultTestStream(nc);
            JetStream js = nc.jetStream();

            jsPublish(js, SUBJECT, 1, 1);
            PushSubscribeOptions pso = PushSubscribeOptions.builder()
                    .durable(DURABLE)
                    .build();
            JetStreamSubscription s = js.subscribe(SUBJECT, pso);
            Message m = s.nextMessage(DEFAULT_TIMEOUT);
            assertNotNull(m);
            assertEquals(data(1), new String(m.getData()));
            m.ack();
            s.unsubscribe();

            jsPublish(js, SUBJECT, 2, 1);
            pso = PushSubscribeOptions.builder()
                    .stream(STREAM)
                    .durable(DURABLE)
                    .bind(true)
                    .build();
            s = js.subscribe(SUBJECT, pso);
            m = s.nextMessage(DEFAULT_TIMEOUT);
            assertNotNull(m);
            assertEquals(data(2), new String(m.getData()));
            m.ack();
            s.unsubscribe();

            jsPublish(js, SUBJECT, 3, 1);
            pso = PushSubscribeOptions.bind(STREAM, DURABLE);
            s = js.subscribe(SUBJECT, pso);
            m = s.nextMessage(DEFAULT_TIMEOUT);
            assertNotNull(m);
            assertEquals(data(3), new String(m.getData()));

            assertThrows(IllegalArgumentException.class,
                    () -> PushSubscribeOptions.builder().stream(STREAM).bind(true).build());

            assertThrows(IllegalArgumentException.class,
                    () -> PushSubscribeOptions.builder().durable(DURABLE).bind(true).build());

            assertThrows(IllegalArgumentException.class,
                    () -> PushSubscribeOptions.builder().stream(EMPTY).bind(true).build());

            assertThrows(IllegalArgumentException.class,
                    () -> PushSubscribeOptions.builder().stream(STREAM).durable(EMPTY).bind(true).build());
        });
    }

    @Test
    public void testBindPull() throws Exception {
        runInJsServer(nc -> {
            createDefaultTestStream(nc);
            JetStream js = nc.jetStream();

            jsPublish(js, SUBJECT, 1, 1);

            PullSubscribeOptions pso = PullSubscribeOptions.builder()
                    .durable(DURABLE)
                    .build();
            JetStreamSubscription s = js.subscribe(SUBJECT, pso);
            s.pull(1);
            Message m = s.nextMessage(DEFAULT_TIMEOUT);
            assertNotNull(m);
            assertEquals(data(1), new String(m.getData()));
            m.ack();
            s.unsubscribe();

            jsPublish(js, SUBJECT, 2, 1);
            pso = PullSubscribeOptions.builder()
                    .stream(STREAM)
                    .durable(DURABLE)
                    .bind(true)
                    .build();
            s = js.subscribe(SUBJECT, pso);
            s.pull(1);
            m = s.nextMessage(DEFAULT_TIMEOUT);
            assertNotNull(m);
            assertEquals(data(2), new String(m.getData()));
            m.ack();
            s.unsubscribe();

            jsPublish(js, SUBJECT, 3, 1);
            pso = PullSubscribeOptions.bind(STREAM, DURABLE);
            s = js.subscribe(SUBJECT, pso);
            s.pull(1);
            m = s.nextMessage(DEFAULT_TIMEOUT);
            assertNotNull(m);
            assertEquals(data(3), new String(m.getData()));
        });
    }

    @Test
    public void testBindErrors() throws Exception {
        runInJsServer(nc -> {
            JetStream js = nc.jetStream();
            createDefaultTestStream(nc);

            // bind errors
            PushSubscribeOptions pushbinderr = PushSubscribeOptions.bind(STREAM, "binddur");
            IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, pushbinderr));
            assertTrue(iae.getMessage().contains(JsSubConsumerNotFoundRequiredInBind.id()));

            PullSubscribeOptions pullbinderr = PullSubscribeOptions.bind(STREAM, "binddur");
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, pullbinderr));
            assertTrue(iae.getMessage().contains(JsSubConsumerNotFoundRequiredInBind.id()));
        });
    }

    @Test
    public void testFilterMismatchErrors() throws Exception {
        runInJsServer(nc -> {
            JetStreamManagement jsm = nc.jetStreamManagement();
            JetStream js = nc.jetStream();

            // single subject
            createMemoryStream(jsm, STREAM, SUBJECT);

            // will work as SubscribeSubject equals Filter Subject
            subscribeOk(js, jsm, SUBJECT, SUBJECT);
            subscribeOk(js, jsm, ">", ">");
            subscribeOk(js, jsm, "*", "*");

            // will work as SubscribeSubject != empty Filter Subject,
            // b/c Stream has exactly 1 subject and is a match.
            subscribeOk(js, jsm, "", SUBJECT);

            // will work as SubscribeSubject != Filter Subject of '>'
            // b/c Stream has exactly 1 subject and is a match.
            subscribeOk(js, jsm, ">", SUBJECT);

            // will not work
            subscribeEx(js, jsm, "*", SUBJECT);

            // multiple subjects no wildcards
            jsm.deleteStream(STREAM);
            createMemoryStream(jsm, STREAM, SUBJECT, subject(2));

            // will work as SubscribeSubject equals Filter Subject
            subscribeOk(js, jsm, SUBJECT, SUBJECT);
            subscribeOk(js, jsm, ">", ">");
            subscribeOk(js, jsm, "*", "*");

            // will not work because stream has more than 1 subject
            subscribeEx(js, jsm, "", SUBJECT);
            subscribeEx(js, jsm, ">", SUBJECT);
            subscribeEx(js, jsm, "*", SUBJECT);

            // multiple subjects via '>'
            jsm.deleteStream(STREAM);
            createMemoryStream(jsm, STREAM, SUBJECT_GT);

            // will work, exact matches
            subscribeOk(js, jsm, subjectDot("1"), subjectDot("1"));
            subscribeOk(js, jsm, ">", ">");

            // will not work because mismatch / stream has more than 1 subject
            subscribeEx(js, jsm, "", subjectDot("1"));
            subscribeEx(js, jsm, ">", subjectDot("1"));
            subscribeEx(js, jsm, SUBJECT_GT, subjectDot("1"));

            // multiple subjects via '*'
            jsm.deleteStream(STREAM);
            createMemoryStream(jsm, STREAM, SUBJECT_STAR);

            // will work, exact matches
            subscribeOk(js, jsm, subjectDot("1"), subjectDot("1"));
            subscribeOk(js, jsm, ">", ">");

            // will not work because mismatch / stream has more than 1 subject
            subscribeEx(js, jsm, "", subjectDot("1"));
            subscribeEx(js, jsm, ">", subjectDot("1"));
            subscribeEx(js, jsm, SUBJECT_STAR, subjectDot("1"));
        });
    }

    private void subscribeOk(JetStream js, JetStreamManagement jsm, String fs, String ss) throws IOException, JetStreamApiException {
        int i = RandomUtils.PRAND.nextInt(); // just want a unique number
        setupConsumer(jsm, i, fs);
        js.subscribe(ss, ConsumerConfiguration.builder().durable(durable(i)).buildPushSubscribeOptions()).unsubscribe();
    }

    private void subscribeEx(JetStream js, JetStreamManagement jsm, String fs, String ss) throws IOException, JetStreamApiException {
        int i = RandomUtils.PRAND.nextInt(); // just want a unique number
        setupConsumer(jsm, i, fs);
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
            () -> js.subscribe(ss, ConsumerConfiguration.builder().durable(durable(i)).buildPushSubscribeOptions()));
        assertTrue(iae.getMessage().contains(JsSubSubjectDoesNotMatchFilter.id()));
    }

    private void setupConsumer(JetStreamManagement jsm, int i, String fs) throws IOException, JetStreamApiException {
        jsm.addOrUpdateConsumer(STREAM,
            ConsumerConfiguration.builder().deliverSubject(deliver(i)).durable(durable(i)).filterSubject(fs).build());
    }

    @Test
    public void testBindDurableDeliverSubject() throws Exception {
        runInJsServer(nc -> {
            JetStreamManagement jsm = nc.jetStreamManagement();
            JetStream js = nc.jetStream();

            // create the stream.
            createDefaultTestStream(jsm);

            // create a durable push subscriber - has deliver subject
            ConsumerConfiguration ccDurPush = ConsumerConfiguration.builder()
                    .durable(durable(1))
                    .deliverSubject(deliver(1))
                    .build();
            jsm.addOrUpdateConsumer(STREAM, ccDurPush);

            // create a durable pull subscriber - notice no deliver subject
            ConsumerConfiguration ccDurPull = ConsumerConfiguration.builder()
                    .durable(durable(2))
                    .build();
            jsm.addOrUpdateConsumer(STREAM, ccDurPull);

            // try to pull subscribe against a push durable
            IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                    () -> js.subscribe(SUBJECT, PullSubscribeOptions.builder().durable(durable(1)).build())
            );
            assertTrue(iae.getMessage().contains(JsSubConsumerAlreadyConfiguredAsPush.id()));

            // try to pull bind against a push durable
            iae = assertThrows(IllegalArgumentException.class,
                    () -> js.subscribe(SUBJECT, PullSubscribeOptions.bind(STREAM, durable(1)))
            );
            assertTrue(iae.getMessage().contains(JsSubConsumerAlreadyConfiguredAsPush.id()));

            // this one is okay
            JetStreamSubscription sub = js.subscribe(SUBJECT, PullSubscribeOptions.builder().durable(durable(2)).build());
            sub.unsubscribe(); // so I can re-use the durable

            // try to push subscribe against a pull durable
            iae = assertThrows(IllegalArgumentException.class,
                    () -> js.subscribe(SUBJECT, PushSubscribeOptions.builder().durable(durable(2)).build())
            );
            assertTrue(iae.getMessage().contains(JsSubConsumerAlreadyConfiguredAsPull.id()));

            // try to push bind against a pull durable
            iae = assertThrows(IllegalArgumentException.class,
                    () -> js.subscribe(SUBJECT, PushSubscribeOptions.bind(STREAM, durable(2)))
            );
            assertTrue(iae.getMessage().contains(JsSubConsumerAlreadyConfiguredAsPull.id()));

            // this one is okay
            js.subscribe(SUBJECT, PushSubscribeOptions.builder().durable(durable(1)).build());
        });
    }

    @Test
    public void testConsumerIsNotModified() throws Exception {
        runInJsServer(nc -> {
            JetStream js = nc.jetStream();
            JetStreamManagement jsm = nc.jetStreamManagement();

            createDefaultTestStream(jsm);

            // test with config in issue 105
            ConsumerConfiguration cc = ConsumerConfiguration.builder()
                .description("desc")
                .ackPolicy(AckPolicy.Explicit)
                .deliverPolicy(DeliverPolicy.All)
                .deliverSubject(deliver(1))
                .deliverGroup(queue(1))
                .durable(durable(1))
                .maxAckPending(65000)
                .maxDeliver(5)
                .maxBatch(10)
                .replayPolicy(ReplayPolicy.Instant)
                .build();
            jsm.addOrUpdateConsumer(STREAM, cc);

            PushSubscribeOptions pushOpts = PushSubscribeOptions.bind(STREAM, durable(1));
            js.subscribe(SUBJECT, queue(1), pushOpts); // should not throw an error

            // testing numerics
            cc = ConsumerConfiguration.builder()
                .deliverPolicy(DeliverPolicy.ByStartSequence)
                .deliverSubject(deliver(21))
                .durable(durable(21))
                .startSequence(42)
                .maxDeliver(43)
                .maxBatch(47)
                .rateLimit(44)
                .maxAckPending(45)
                .build();
            jsm.addOrUpdateConsumer(STREAM, cc);

            pushOpts = PushSubscribeOptions.bind(STREAM, durable(21));
            js.subscribe(SUBJECT, pushOpts); // should not throw an error

            cc = ConsumerConfiguration.builder()
                .durable(durable(22))
                .maxPullWaiting(46)
                .build();
            jsm.addOrUpdateConsumer(STREAM, cc);

            PullSubscribeOptions pullOpts = PullSubscribeOptions.bind(STREAM, durable(22));
            js.subscribe(SUBJECT, pullOpts); // should not throw an error

            // testing DateTime
            cc = ConsumerConfiguration.builder()
                .deliverPolicy(DeliverPolicy.ByStartTime)
                .deliverSubject(deliver(3))
                .durable(durable(3))
                .startTime(ZonedDateTime.now().plusHours(1))
                .build();
            jsm.addOrUpdateConsumer(STREAM, cc);

            pushOpts = PushSubscribeOptions.bind(STREAM, durable(3));
            js.subscribe(SUBJECT, pushOpts); // should not throw an error

            // testing boolean and duration
            cc = ConsumerConfiguration.builder()
                .deliverSubject(deliver(4))
                .durable(durable(4))
                .flowControl(1000)
                .headersOnly(true)
                .maxExpires(30000)
                .inactiveThreshold(40000)
                .ackWait(2000)
                .build();
            jsm.addOrUpdateConsumer(STREAM, cc);

            pushOpts = PushSubscribeOptions.bind(STREAM, durable(4));
            js.subscribe(SUBJECT, pushOpts); // should not throw an error

            // testing enums
            cc = ConsumerConfiguration.builder()
                .deliverSubject(deliver(5))
                .durable(durable(5))
                .deliverPolicy(DeliverPolicy.Last)
                .ackPolicy(AckPolicy.None)
                .replayPolicy(ReplayPolicy.Original)
                .build();
            jsm.addOrUpdateConsumer(STREAM, cc);

            pushOpts = PushSubscribeOptions.bind(STREAM, durable(5));
            js.subscribe(SUBJECT, pushOpts); // should not throw an error
        });
    }

    @Test
    public void testConsumerCannotBeModified() throws Exception {
        runInJsServer(nc -> {
            JetStream js = nc.jetStream();
            JetStreamManagement jsm = nc.jetStreamManagement();

            createDefaultTestStream(jsm);

            ConsumerConfiguration.Builder builder = durBuilder();
            nc.jetStreamManagement().addOrUpdateConsumer(STREAM, builder.build());

            ccbmEx(js, durBuilder().deliverPolicy(DeliverPolicy.Last));
            ccbmEx(js, durBuilder().deliverPolicy(DeliverPolicy.New));
            ccbmEx(js, durBuilder().ackPolicy(AckPolicy.None));
            ccbmEx(js, durBuilder().ackPolicy(AckPolicy.All));
            ccbmEx(js, durBuilder().replayPolicy(ReplayPolicy.Original));

            ccbmEx(js, durBuilder().startTime(ZonedDateTime.now()));
            ccbmEx(js, durBuilder().ackWait(Duration.ofMillis(1)));
            ccbmEx(js, durBuilder().description("x"));
            ccbmEx(js, durBuilder().sampleFrequency("x"));
            ccbmEx(js, durBuilder().idleHeartbeat(Duration.ofMillis(1000)));
            ccbmEx(js, durBuilder().maxExpires(Duration.ofMillis(1000)));
            ccbmEx(js, durBuilder().inactiveThreshold(Duration.ofMillis(1000)));

            ccbmEx(js, durBuilder().startSequence(5));
            ccbmEx(js, durBuilder().maxDeliver(5));
            ccbmEx(js, durBuilder().rateLimit(5));
            ccbmEx(js, durBuilder().maxAckPending(5));
            ccbmEx(js, durBuilder().maxBatch(5));

            ccbmOk(js, durBuilder().startSequence(0));
            ccbmOk(js, durBuilder().startSequence(-1));
            ccbmOk(js, durBuilder().maxDeliver(0));
            ccbmOk(js, durBuilder().maxDeliver(-1));
            ccbmOk(js, durBuilder().rateLimit(0));
            ccbmOk(js, durBuilder().rateLimit(-1));
            ccbmOk(js, durBuilder().maxAckPending(0));
            ccbmOk(js, durBuilder().maxAckPending(-1));
            ccbmOk(js, durBuilder().maxAckPending(20000)); // 20000 is the default set by the server
            ccbmOk(js, durBuilder().maxPullWaiting(0));
            ccbmOk(js, durBuilder().maxPullWaiting(-1));
            ccbmOk(js, durBuilder().maxBatch(0));
            ccbmOk(js, durBuilder().maxBatch(-1));
            ccbmOk(js, durBuilder().ackWait(Duration.ofSeconds(30)));

            ConsumerConfiguration.Builder builder2 = ConsumerConfiguration.builder().durable(durable(2));
            nc.jetStreamManagement().addOrUpdateConsumer(STREAM, builder2.build());
            ccbmExPull(js, builder2.maxPullWaiting(999));
            ccbmOkPull(js, builder2.maxPullWaiting(512)); // 512 is the default

            jsm.deleteConsumer(STREAM, DURABLE);

            builder = durBuilder()
                .startSequence(5)
                .maxDeliver(6)
                .rateLimit(7)
                .maxAckPending(8)
                .deliverPolicy(DeliverPolicy.ByStartSequence);

            jsm.addOrUpdateConsumer(STREAM, builder.build());
            ccbmEx(js, builder.startSequence(55));
            ccbmEx(js, builder.maxDeliver(66));
            ccbmEx(js, builder.rateLimit(77));
            ccbmEx(js, builder.maxAckPending(88));
        });
    }

    static class ConsumerConfigurationChecker extends ConsumerConfiguration {
        public ConsumerConfigurationChecker(ConsumerConfiguration cc) {
            super(cc);
        }
        public Duration ackWait() { return ackWait; }
        public Long maxDeliver() { return maxDeliver; }
        public Long maxAckPending() { return maxAckPending; }
        public Long maxPullWaiting() { return maxPullWaiting; }
    }

    // default json from server for reference
    //    "durable_name": "durable",
    //    "deliver_policy": "all",
    //    "ack_policy": "explicit",
    //    "ack_wait": 30000000000,
    //    "max_deliver": -1,
    //    "replay_policy": "instant",
    //    "max_waiting": 512,
    //    "max_ack_pending": 20000
    @Test
    public void testDefaultConsumerConfiguration() throws Exception {
        runInJsServer(nc -> {
            JetStreamManagement jsm = nc.jetStreamManagement();

            createDefaultTestStream(jsm);

            ConsumerConfiguration cc =
                ConsumerConfiguration.builder()
                    .durable(DURABLE).build();
            cc = jsm.addOrUpdateConsumer(STREAM, cc).getConsumerConfiguration();
            ConsumerConfigurationChecker ccc = new ConsumerConfigurationChecker(cc);
            assertEquals(Duration.ofSeconds(30), ccc.ackWait());
            assertEquals(-1, ccc.maxDeliver());
            assertEquals(512, ccc.maxPullWaiting());
            assertEquals(20000, ccc.maxAckPending());
        });
    }


    private void ccbmOk(JetStream js, ConsumerConfiguration.Builder builder) throws IOException, JetStreamApiException {
        js.subscribe(SUBJECT, PushSubscribeOptions.builder().configuration(builder.build()).build()).unsubscribe();
    }

    private void ccbmOkPull(JetStream js, ConsumerConfiguration.Builder builder) throws IOException, JetStreamApiException {
        js.subscribe(SUBJECT, PullSubscribeOptions.builder().configuration(builder.build()).build()).unsubscribe();
    }

    private void ccbmEx(JetStream js, ConsumerConfiguration.Builder builder) {
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
            () -> js.subscribe(SUBJECT, PushSubscribeOptions.builder().configuration(builder.build()).build()));
        assertTrue(iae.getMessage().contains(JsSubExistingConsumerCannotBeModified.id()));
    }

    private void ccbmExPull(JetStream js, ConsumerConfiguration.Builder builder) {
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
            () -> js.subscribe(SUBJECT, PullSubscribeOptions.builder().configuration(builder.build()).build()));
        assertTrue(iae.getMessage().contains(JsSubExistingConsumerCannotBeModified.id()));
    }

    private ConsumerConfiguration.Builder durBuilder() {
        return ConsumerConfiguration.builder().durable(DURABLE).deliverSubject(DELIVER);
    }

    @Test
    public void testGetConsumerInfoFromSubscription() throws Exception {
        runInJsServer(nc -> {
            // Create our JetStream context.
            JetStream js = nc.jetStream();

            // create the stream.
            createDefaultTestStream(nc);

            JetStreamSubscription sub = js.subscribe(SUBJECT);
            nc.flush(Duration.ofSeconds(1)); // flush outgoing communication with/to the server

            ConsumerInfo ci = sub.getConsumerInfo();
            assertEquals(STREAM, ci.getStreamName());
        });
    }

    @Test
    public void testInternalLookupConsumerInfoCoverage() throws Exception {
        runInJsServer(nc -> {
            JetStream js = nc.jetStream();

            // create the stream.
            createDefaultTestStream(nc);

            // - consumer not found
            // - stream does not exist
            JetStreamSubscription sub = js.subscribe(SUBJECT);
            assertNull(((NatsJetStream)js).lookupConsumerInfo(STREAM, DURABLE));
            assertThrows(JetStreamApiException.class,
                    () -> ((NatsJetStream)js).lookupConsumerInfo(stream(999), DURABLE));
        });
    }

    @Test
    public void testGetJetStreamValidatedConnectionCoverage() {
        NatsJetStreamMessage njsm = new NatsJetStreamMessage();

        IllegalStateException ise = assertThrows(IllegalStateException.class, njsm::getJetStreamValidatedConnection);
        assertTrue(ise.getMessage().contains("subscription"));

        njsm.subscription = new NatsSubscription("sid", "sub", "q", null, null);
        ise = assertThrows(IllegalStateException.class, njsm::getJetStreamValidatedConnection);
        assertTrue(ise.getMessage().contains("connection"));
    }

    @Test
    public void testMoreCreateSubscriptionErrors() throws Exception {
        runInJsServer(nc -> {
            JetStream js = nc.jetStream();

            IllegalStateException ise = assertThrows(IllegalStateException.class, () -> js.subscribe(SUBJECT));
            assertTrue(ise.getMessage().contains(JsSubNoMatchingStreamForSubject.id()));

            // create the stream.
            createDefaultTestStream(nc);

            // general pull push validation
            ConsumerConfiguration ccCantHave = ConsumerConfiguration.builder().durable("pulldur").deliverGroup("cantHave").build();
            PullSubscribeOptions pullCantHaveDlvrGrp = PullSubscribeOptions.builder().configuration(ccCantHave).build();
            IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, pullCantHaveDlvrGrp));
            assertTrue(iae.getMessage().contains(JsSubPullCantHaveDeliverGroup.id()));

            ccCantHave = ConsumerConfiguration.builder().durable("pulldur").deliverSubject("cantHave").build();
            PullSubscribeOptions pullCantHaveDlvrSub = PullSubscribeOptions.builder().configuration(ccCantHave).build();
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, pullCantHaveDlvrSub));
            assertTrue(iae.getMessage().contains(JsSubPullCantHaveDeliverSubject.id()));

            ccCantHave = ConsumerConfiguration.builder().maxPullWaiting(1L).build();
            PushSubscribeOptions pushCantHaveMpw = PushSubscribeOptions.builder().configuration(ccCantHave).build();
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, pushCantHaveMpw));
            assertTrue(iae.getMessage().contains(JsSubPushCantHaveMaxPullWaiting.id()));

            // create some consumers
            PushSubscribeOptions psoDurNoQ = PushSubscribeOptions.builder().durable("durNoQ").build();
            js.subscribe(SUBJECT, psoDurNoQ);

            PushSubscribeOptions psoDurYesQ = PushSubscribeOptions.builder().durable("durYesQ").build();
            js.subscribe(SUBJECT, "yesQ", psoDurYesQ);

            // already bound
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, psoDurNoQ));
            assertTrue(iae.getMessage().contains(JsSubConsumerAlreadyBound.id()));

            // queue match
            PushSubscribeOptions qmatch = PushSubscribeOptions.builder()
                .durable("qmatchdur").deliverGroup("qmatchq").build();
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, "qnotmatch", qmatch));
            assertTrue(iae.getMessage().contains(JsSubQueueDeliverGroupMismatch.id()));

            // queue vs config
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, "notConfigured", psoDurNoQ));
            assertTrue(iae.getMessage().contains(JsSubExistingConsumerNotQueue.id()));

            PushSubscribeOptions psoNoVsYes = PushSubscribeOptions.builder().durable("durYesQ").build();
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, psoNoVsYes));
            assertTrue(iae.getMessage().contains(JsSubExistingConsumerIsQueue.id()));

            PushSubscribeOptions psoYesVsNo = PushSubscribeOptions.builder().durable("durYesQ").build();
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, "qnotmatch", psoYesVsNo));
            assertTrue(iae.getMessage().contains(JsSubExistingQueueDoesNotMatchRequestedQueue.id()));

            // flow control heartbeat push / pull
            ConsumerConfiguration ccFc = ConsumerConfiguration.builder().durable("ccFcDur").flowControl(1000).build();
            ConsumerConfiguration ccHb = ConsumerConfiguration.builder().durable("ccHbDur").idleHeartbeat(1000).build();

            PullSubscribeOptions psoPullCcFc = PullSubscribeOptions.builder().configuration(ccFc).build();
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, psoPullCcFc));
            assertTrue(iae.getMessage().contains(JsSubFcHbNotValidPull.id()));

            PullSubscribeOptions psoPullCcHb = PullSubscribeOptions.builder().configuration(ccHb).build();
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, psoPullCcHb));
            assertTrue(iae.getMessage().contains(JsSubFcHbNotValidPull.id()));

            PushSubscribeOptions psoPushCcFc = PushSubscribeOptions.builder().configuration(ccFc).build();
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, "cantHaveQ", psoPushCcFc));
            assertTrue(iae.getMessage().contains(JsSubFcHbHbNotValidQueue.id()));

            PushSubscribeOptions psoPushCcHb = PushSubscribeOptions.builder().configuration(ccHb).build();
            iae = assertThrows(IllegalArgumentException.class, () -> js.subscribe(SUBJECT, "cantHaveQ", psoPushCcHb));
            assertTrue(iae.getMessage().contains(JsSubFcHbHbNotValidQueue.id()));
        });
    }
}