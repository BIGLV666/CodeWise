import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.sun.net.httpserver.HttpServer;
import org.example.servicecommon.config.*;
import org.example.servicecommon.RedisDto.*;
import org.example.servicecommon.dto.WebsocketSendDto;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicequestion.handle.DebugHandle;
import org.example.servicequestion.MQ.Mq;
import org.example.servicequestion.service.FunctionTestCaseGenerationService;
import org.example.servicejudge.Mq.consumer.JudgeDebugConsumer;
import org.example.servicejudge.Mq.handler.JudgeDebugHandler;
import org.example.servicejudge.mapper.QuestionMapper;
import org.example.servicejudge.mapper.FunctionConfigMapper;
import org.example.servicejudge.judge.JudgeService;
import org.example.servicejudge.entry.Question;
import org.example.servicejudge.enums.QuestionType;
import org.example.servicejudge.Dto.JudgeReturnDto;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.example.servicecommon.config.MqContexts.*;

/**
 * Isolated integration + load harness for the debug-judging message chain.
 *
 * <p>Uses the REAL application classes: {@code MqConfig} topology, both service
 * {@code RabbitTemplateConfig} beans, {@code DebugHandle}, {@code Mq} dispatcher,
 * {@code JudgeDebugHandler} and {@code JudgeDebugConsumer}. Only two things are
 * stubbed: the judge-side DB lookup (question row) and code execution
 * ({@code JudgeService.executeCode}) — the container/Docker judge is not run here.
 *
 * <p>The HTTP phase uses a local {@link HttpServer} stand-in that mirrors the
 * production controller response shape ({@code Result{code,data,message}}, HTTP 200).
 * It is a SIMULATED acceptance endpoint: no real Spring controller, gateway,
 * servlet stack, STOMP session or browser is exercised.
 */
public class DebugJudgeHarness implements AutoCloseable {
    static final String RUN_ID = "debug-judge-" + Instant.now().toString().replace(':', '-');
    final ObjectMapper json = new ObjectMapper();
    final CachingConnectionFactory cf;
    final AnnotationConfigApplicationContext topology;
    final LettuceConnectionFactory rcf;
    final RedisTemplate<String, Object> redis;
    final RabbitTemplate question, judge;
    final Mq dispatcher;
    final JudgeDebugConsumer judgeConsumer;
    final AtomicLong acks = new AtomicLong(), nacks = new AtomicLong(), aborts = new AtomicLong();
    final AtomicLong doubleSettlements = new AtomicLong();
    /** Settlement bookkeeping is keyed by the real channel so double settlement is
     *  detected across separate per-delivery proxies wrapping the same consumer channel. */
    final Map<Channel, Set<Long>> settledByChannel = new ConcurrentHashMap<>();
    final AtomicLong sequence = new AtomicLong(1000), outputs = new AtomicLong();
    final List<SimpleMessageListenerContainer> listeners = new ArrayList<>();
    final List<Map<String, Object>> checks = new ArrayList<>();

    DebugJudgeHarness(int rabbitPort, int redisPort) {
        cf = new CachingConnectionFactory("127.0.0.1", rabbitPort);
        cf.setUsername("debugtest");
        cf.setPassword("isolated-test-only");
        cf.setChannelCacheSize(64);
        topology = new AnnotationConfigApplicationContext();
        topology.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("isolated", Map.of("codewise.mq.enabled", "true")));
        topology.register(MqConfig.class);
        topology.refresh();
        RabbitAdmin admin = new RabbitAdmin(cf);
        admin.setApplicationContext(topology);
        admin.initialize();
        rcf = new LettuceConnectionFactory("127.0.0.1", redisPort);
        rcf.afterPropertiesSet();
        rcf.start();
        redis = new RedisConfig().redisTemplate(rcf);
        question = questionTemplate();
        judge = judgeTemplate();
        DebugHandle handle = new DebugHandle();
        ReflectionTestUtils.setField(handle, "rabbitTemplate", question);
        ReflectionTestUtils.setField(handle, "redisTemplate", redis);
        // isGenerationTask() reads real Redis; no synthetic generation task is registered,
        // so the ordinary debug branch is what runs (see generation-* checks for the other path).
        var generation = new FunctionTestCaseGenerationService(null, null, null, null, null, redis, question);
        ReflectionTestUtils.setField(handle, "functionTestCaseGenerationService", generation);
        dispatcher = new Mq(List.of(handle));
        dispatcher.init();
        QuestionMapper mapper = mock(QuestionMapper.class);
        Question q = new Question();
        q.setQuestionType(QuestionType.ACM);
        when(mapper.selectById(anyLong())).thenReturn(q);
        JudgeService execution = mock(JudgeService.class);
        when(execution.executeCode(anyString(), anyString(), anyString())).thenReturn(
                JudgeReturnDto.builder().exitCode(0).stdout("2").timeUsed(1).memoryUsed(1).build());
        judgeConsumer = new JudgeDebugConsumer(
                new JudgeDebugHandler(judge, redis, mapper, execution, mock(FunctionConfigMapper.class)));
    }

    /** Exercises the production RabbitTemplate config bean (not a hand-rolled template). */
    static RabbitTemplate questionTemplate(CachingConnectionFactory cf) {
        return configured(new org.example.servicequestion.config.RabbitTemplateConfig(), cf);
    }

    static RabbitTemplate judgeTemplate(CachingConnectionFactory cf) {
        return configured(new org.example.servicejudge.config.RabbitTemplateConfig(), cf);
    }

    @SuppressWarnings("unchecked")
    static RabbitTemplate configured(Object configBean, CachingConnectionFactory cf) {
        RabbitTemplateConfigurer configurer = new RabbitTemplateConfigurer(new RabbitProperties());
        ObjectProvider<RabbitTemplateCustomizer> customizers = mock(ObjectProvider.class);
        when(customizers.orderedStream()).thenReturn(Stream.empty());
        if (configBean instanceof org.example.servicequestion.config.RabbitTemplateConfig q) {
            return q.rabbitTemplate(configurer, cf, customizers);
        }
        return ((org.example.servicejudge.config.RabbitTemplateConfig) configBean)
                .rabbitTemplate(configurer, cf, customizers);
    }

    RabbitTemplate questionTemplate() {
        return questionTemplate(cf);
    }

    RabbitTemplate judgeTemplate() {
        return judgeTemplate(cf);
    }

    void check(String name, boolean pass, Object detail) {
        checks.add(new LinkedHashMap<>(Map.of("test", name, "pass", pass, "detail", String.valueOf(detail))));
        System.err.println((pass ? "PASS  " : "FAIL  ") + name + "  :: " + detail);
    }

    Channel counted(Channel real) {
        return counted(real, new AtomicLong(), new AtomicLong());
    }

    Channel counted(Channel real, AtomicLong ack, AtomicLong nack) {
        return (Channel) java.lang.reflect.Proxy.newProxyInstance(
                Channel.class.getClassLoader(), new Class<?>[]{Channel.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if ("basicAck".equals(name) || "basicNack".equals(name)) {
                        Set<Long> settled = settledByChannel.computeIfAbsent(real, k -> ConcurrentHashMap.newKeySet());
                        if (!settled.add((Long) args[0])) {
                            doubleSettlements.incrementAndGet();
                        }
                        // Duplicate settlement always happens inside one delivery's handling, so the
                        // tag set only needs recent history. Keeping every tag would itself allocate
                        // hundreds of MiB over a long soak and swamp the heap-leak signal.
                        if (settled.size() > 100_000) {
                            settled.clear();
                        }
                        if ("basicAck".equals(name)) {
                            acks.incrementAndGet();
                            ack.incrementAndGet();
                        } else {
                            nacks.incrementAndGet();
                            nack.incrementAndGet();
                        }
                    }
                    if ("abort".equals(name)) {
                        aborts.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args == null ? new Object[0] : args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    Message get(Channel c, String queue) throws Exception {
        GetResponse g = null;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (g == null && System.nanoTime() < until) {
            g = c.basicGet(queue, false);
            if (g == null) {
                Thread.sleep(5);
            }
        }
        if (g == null) {
            throw new IllegalStateException("no message in " + queue);
        }
        MessageProperties p = new MessageProperties();
        p.setDeliveryTag(g.getEnvelope().getDeliveryTag());
        p.setReceivedRoutingKey(g.getEnvelope().getRoutingKey());
        p.setContentType(g.getProps().getContentType());
        return new Message(g.getBody(), p);
    }

    String seed(long id) {
        String uuid = UUID.randomUUID().toString();
        JudgeReturnRecordDto r = new JudgeReturnRecordDto();
        r.setUserId(id);
        r.setSubmitStatus("AC");
        r.setUserOutput("2");
        redis.opsForHash().put(RedisContext.JUDGE_RESULT_KEY, uuid, new ArrayList<>(List.of(r)));
        return uuid;
    }

    void purge() {
        for (String q : List.of(Question_QUEUE_NAME, MESSAGE_QUEUE_NAME, JUDGE_DEBUG_QUEUE, JUDGE_DLQ)) {
            try {
                new RabbitAdmin(cf).purgeQueue(q, false);
            } catch (Exception ignored) {
                // queue may not exist yet; declaration above should have created it
            }
        }
    }

    void integration() throws Exception {
        purge();
        var queues = topology.getBeansOfType(org.springframework.amqp.core.Queue.class);
        try (var conn = cf.createConnection(); var c = conn.createChannel(false)) {
            for (var q : queues.values()) {
                c.queueDeclarePassive(q.getName());
            }
            for (var e : topology.getBeansOfType(Exchange.class).values()) {
                c.exchangeDeclarePassive(e.getName());
            }
            check("common MqConfig declares full topology", !queues.isEmpty(),
                    "queues=" + queues.size()
                            + ", exchanges=" + topology.getBeansOfType(Exchange.class).size()
                            + ", bindings=" + topology.getBeansOfType(Binding.class).size());
        }

        for (var template : List.of(Map.entry("question", question), Map.entry("judge", judge))) {
            String who = template.getKey();
            RabbitTemplate t = template.getValue();
            check(who + " RabbitTemplate uses Jackson2JsonMessageConverter",
                    t.getMessageConverter() instanceof Jackson2JsonMessageConverter,
                    t.getMessageConverter().getClass().getSimpleName());

            WebsocketSendDto dto = new WebsocketSendDto();
            dto.setUserId(7L);
            dto.setQueueName("debug-judge-result");
            dto.setResult(List.of("AC"));
            t.convertAndSend(MESSAGE_EXCHANGE, WEBSOCKET_ROUTING_KEY, dto);
            Message out = t.receive(MESSAGE_QUEUE_NAME, 3000);
            check(who + " sends non-Serializable WebsocketSendDto as JSON",
                    out != null && json.readTree(out.getBody()).get("userId").asLong() == 7,
                    "contentType=" + (out == null ? "missing" : out.getMessageProperties().getContentType()));

            t.convertAndSend(Question_EXCHANGE, QUESTION_DEBUG_ROUTING_KEY, "abc-uuid");
            Message uuid = t.receive(Question_QUEUE_NAME, 3000);
            check(who + " encodes UUID callback as quoted JSON string",
                    uuid != null && new String(uuid.getBody(), StandardCharsets.UTF_8).equals("\"abc-uuid\""),
                    uuid == null ? "missing" : new String(uuid.getBody(), StandardCharsets.UTF_8));
        }

        for (String mode : List.of("bare", "quoted", "envelope", "reencoded-envelope")) {
            String uuid = seed(sequence.incrementAndGet());
            try (var conn = cf.createConnection(); var c = conn.createChannel(false)) {
                var ack = new AtomicLong();
                var nack = new AtomicLong();
                var channel = counted(c, ack, nack);
                String envelope = EnvelopeCodec.serialize(
                        EnvelopeCodec.wrap("DEBUG_RESULT", "judge", "trace-" + uuid, uuid));
                switch (mode) {
                    case "quoted" -> judge.convertAndSend(Question_EXCHANGE, QUESTION_DEBUG_ROUTING_KEY, uuid);
                    // raw envelope body, exactly as OutboxPro publishes it (no JSON re-encoding)
                    case "envelope" -> c.basicPublish(Question_EXCHANGE, QUESTION_DEBUG_ROUTING_KEY, null,
                            envelope.getBytes(StandardCharsets.UTF_8));
                    // envelope String sent through the Jackson converter: wire body is JSON-quoted JSON
                    case "reencoded-envelope" -> judge.convertAndSend(Question_EXCHANGE, QUESTION_DEBUG_ROUTING_KEY, envelope);
                    default -> c.basicPublish(Question_EXCHANGE, QUESTION_DEBUG_ROUTING_KEY, null,
                            uuid.getBytes(StandardCharsets.UTF_8));
                }
                Message in = get(c, Question_QUEUE_NAME);
                dispatcher.mq(in, QUESTION_DEBUG_ROUTING_KEY, channel, in);
                Message out = question.receive(MESSAGE_QUEUE_NAME, 1500);
                boolean ok = out != null
                        && ack.get() == 1 && nack.get() == 0
                        && "success".equals(redis.opsForValue().get(RedisContext.QUESTION_SUCCESS_KEY + uuid))
                        && redis.opsForHash().get(RedisContext.JUDGE_RESULT_KEY, uuid) == null;
                check(mode + " UUID -> DebugHandle -> message.queue, exactly one ACK", ok,
                        "ack=" + ack + ", nack=" + nack + ", forwarded=" + (out != null));

                long before = ack.get();
                c.basicPublish(Question_EXCHANGE, QUESTION_DEBUG_ROUTING_KEY, null,
                        uuid.getBytes(StandardCharsets.UTF_8));
                Message dup = get(c, Question_QUEUE_NAME);
                dispatcher.mq(dup, QUESTION_DEBUG_ROUTING_KEY, channel, dup);
                check(mode + " duplicate completed callback ACKs once and sends no second result",
                        ack.get() == before + 1 && question.receive(MESSAGE_QUEUE_NAME, 300) == null,
                        "ackDelta=" + (ack.get() - before));
            }
        }

        // Pending duplicate: success key leased but not completed -> bounded backoff then requeue, no settlement loss.
        {
            String uuid = UUID.randomUUID().toString();
            redis.opsForValue().set(RedisContext.QUESTION_SUCCESS_KEY + uuid, "pending", 5, TimeUnit.MINUTES);
            try (var conn = cf.createConnection(); var c = conn.createChannel(false)) {
                var ack = new AtomicLong();
                var nack = new AtomicLong();
                var channel = counted(c, ack, nack);
                c.basicPublish(Question_EXCHANGE, QUESTION_DEBUG_ROUTING_KEY, null,
                        uuid.getBytes(StandardCharsets.UTF_8));
                Message in = get(c, Question_QUEUE_NAME);
                long start = System.nanoTime();
                dispatcher.mq(in, QUESTION_DEBUG_ROUTING_KEY, channel, in);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                check("pending duplicate waits ~1s then requeues (requeue=true), never ACKs unfinished work",
                        nack.get() == 1 && ack.get() == 0 && elapsedMs >= 900,
                        "ack=" + ack + ", nack=" + nack + ", elapsedMs=" + elapsedMs);
            } finally {
                redis.delete(RedisContext.QUESTION_SUCCESS_KEY + uuid);
                // requeue=true put the message back; drain it so later scenarios see a clean queue
                new RabbitAdmin(cf).purgeQueue(Question_QUEUE_NAME, false);
            }
        }

        // Valid UUID with no Redis result at all: discarded exactly once, channel stays usable.
        {
            String uuid = UUID.randomUUID().toString();
            try (var conn = cf.createConnection(); var c = conn.createChannel(false)) {
                var ack = new AtomicLong();
                var nack = new AtomicLong();
                var channel = counted(c, ack, nack);
                c.basicPublish(Question_EXCHANGE, QUESTION_DEBUG_ROUTING_KEY, null,
                        uuid.getBytes(StandardCharsets.UTF_8));
                Message bad = get(c, Question_QUEUE_NAME);
                dispatcher.mq(bad, QUESTION_DEBUG_ROUTING_KEY, channel, bad);
                check("valid UUID with missing Redis result: single NACK(false) and pending lease released",
                        nack.get() == 1 && ack.get() == 0 && c.isOpen()
                                && redis.opsForValue().get(RedisContext.QUESTION_SUCCESS_KEY + uuid) == null,
                        "ack=" + ack + ", nack=" + nack + ", channelOpen=" + c.isOpen());
            }
        }

        // Malformed payload: never touches Redis, discarded once.
        try (var conn = cf.createConnection(); var c = conn.createChannel(false)) {
            var ack = new AtomicLong();
            var nack = new AtomicLong();
            var channel = counted(c, ack, nack);
            c.basicPublish(Question_EXCHANGE, QUESTION_DEBUG_ROUTING_KEY, null, "not-a-uuid".getBytes(StandardCharsets.UTF_8));
            Message bad = get(c, Question_QUEUE_NAME);
            dispatcher.mq(bad, QUESTION_DEBUG_ROUTING_KEY, channel, bad);
            check("malformed task id discarded once (NACK false), no Redis write",
                    nack.get() == 1 && ack.get() == 0,
                    "ack=" + ack + ", nack=" + nack);
        }

        // Unknown routing key: outer dispatcher must discard without a handler.
        try (var conn = cf.createConnection(); var c = conn.createChannel(false)) {
            var ack = new AtomicLong();
            var nack = new AtomicLong();
            var channel = counted(c, ack, nack);
            // default exchange -> question.queue directly, so the body lands while the
            // dispatcher is still asked for a routing key that has no handler
            c.basicPublish("", Question_QUEUE_NAME, null, "x".getBytes(StandardCharsets.UTF_8));
            Message unknown = get(c, Question_QUEUE_NAME);
            dispatcher.mq(unknown, "question.unknown.routing", channel, unknown);
            check("unknown routing key discarded once (NACK false), no crash",
                    nack.get() == 1 && ack.get() == 0, "ack=" + ack + ", nack=" + nack);
        }

        check("no double ACK/NACK across all integration scenarios",
                doubleSettlements.get() == 0, "doubleSettlements=" + doubleSettlements.get());
        purge();
    }

    void listen(String queue, ChannelAwareMessageListener callback, int consumers) {
        var l = new SimpleMessageListenerContainer(cf);
        l.setQueueNames(queue);
        l.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        l.setConcurrentConsumers(consumers);
        l.setMaxConcurrentConsumers(consumers);
        l.setPrefetchCount(32);
        l.setMessageListener((ChannelAwareMessageListener) (m, c) -> callback.onMessage(m, counted(c)));
        l.start();
        listeners.add(l);
    }

    void consumers() {
        listen(Question_QUEUE_NAME,
                (m, c) -> dispatcher.mq(m, m.getMessageProperties().getReceivedRoutingKey(), c, m), 4);
        listen(MESSAGE_QUEUE_NAME, (m, c) -> {
            try {
                long id = json.readTree(m.getBody()).get("userId").asLong();
                Long start = starts.remove(id);
                if (start != null) {
                    outputs.incrementAndGet();
                    var future = done.remove(id);
                    if (future != null) {
                        future.complete(System.nanoTime() - start);
                    }
                }
            } catch (Exception ignored) {
                // non-correlatable message (e.g. baseline DTO publish probe); still ack it
            } finally {
                c.basicAck(m.getMessageProperties().getDeliveryTag(), false);
            }
        }, 2);
        listen(JUDGE_DEBUG_QUEUE, (m, c) -> judgeConsumer.onMessage(m, c), 2);
    }

    final Map<Long, Long> starts = new ConcurrentHashMap<>();
    final Map<Long, CompletableFuture<Long>> done = new ConcurrentHashMap<>();
    final AtomicLong latencySamples = new AtomicLong();
    final List<Long> sampledLatencies = Collections.synchronizedList(new ArrayList<>());
    final AtomicLong inFlight = new AtomicLong();

    Map<String, Object> load(String name, int seconds, int workers, boolean jackson, boolean dtoOnly)
            throws Exception {
        setConverters(jackson);
        AtomicLong attempts = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        sampledLatencies.clear();
        latencySamples.set(0);
        long begin = System.nanoTime();
        long end = begin + TimeUnit.SECONDS.toNanos(seconds);
        long cpuBegin = cpu();
        var pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> tasks = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            tasks.add(pool.submit(() -> {
                while (System.nanoTime() < end) {
                    long start = System.nanoTime();
                    long id = sequence.incrementAndGet();
                    attempts.incrementAndGet();
                    inFlight.incrementAndGet();
                    try {
                        if (dtoOnly) {
                            WebsocketSendDto dto = new WebsocketSendDto();
                            dto.setUserId(id);
                            dto.setQueueName("debug-judge-result");
                            dto.setResult("probe");
                            question.convertAndSend(MESSAGE_EXCHANGE, WEBSOCKET_ROUTING_KEY, dto);
                            sample(System.nanoTime() - start);
                        } else {
                            String uuid = seed(id);
                            var future = new CompletableFuture<Long>();
                            starts.put(id, start);
                            done.put(id, future);
                            judge.convertAndSend(Question_EXCHANGE, QUESTION_DEBUG_ROUTING_KEY, uuid);
                            sample(future.get(3, TimeUnit.SECONDS));
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        starts.remove(id);
                        done.remove(id);
                        sample(System.nanoTime() - start);
                        // a fail-fast publish error would otherwise spin millions of
                        // attempts/sec and drown the measurement; back off 1ms instead
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }
            }));
        }
        for (var f : tasks) {
            f.get();
        }
        pool.shutdown();
        double elapsed = (System.nanoTime() - begin) / 1e9;
        double processCpu = (cpu() - cpuBegin) / 1e9 / elapsed * 100;
        var sorted = new ArrayList<>(sampledLatencies);
        Collections.sort(sorted);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", name);
        result.put("seconds", round(elapsed));
        result.put("workers", workers);
        result.put("converter", jackson ? "Jackson2JsonMessageConverter" : "SimpleMessageConverter");
        result.put("attempts", attempts.get());
        result.put("errors", errors.get());
        result.put("errorRatePct", attempts.get() == 0 ? 0 : round(errors.get() * 100.0 / attempts.get()));
        result.put("attemptQps", round(attempts.get() / elapsed));
        result.put("successQps", round((attempts.get() - errors.get()) / elapsed));
        result.put("p50Ms", pct(sorted, .50));
        result.put("p95Ms", pct(sorted, .95));
        result.put("p99Ms", pct(sorted, .99));
        result.put("samples", sorted.size());
        result.put("processCpuPct", round(processCpu));
        result.put("heapUsedMiB", round((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0));
        result.put("acks", acks.get());
        result.put("nacks", nacks.get());
        result.put("aborts", aborts.get());
        result.put("doubleSettlements", doubleSettlements.get());
        System.err.println("RESULT " + json.writeValueAsString(result));
        return result;
    }

    /** Long-running stability phase with 30s windows so heap/connection drift is visible. */
    Map<String, Object> soak(int seconds, int workers) throws Exception {
        setConverters(true);
        List<Map<String, Object>> windows = new ArrayList<>();
        AtomicLong totalAttempts = new AtomicLong();
        AtomicLong totalErrors = new AtomicLong();
        long begin = System.nanoTime();
        long end = begin + TimeUnit.SECONDS.toNanos(seconds);
        var pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> tasks = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            tasks.add(pool.submit(() -> {
                while (System.nanoTime() < end) {
                    long id = sequence.incrementAndGet();
                    totalAttempts.incrementAndGet();
                    try {
                        String uuid = seed(id);
                        var future = new CompletableFuture<Long>();
                        starts.put(id, System.nanoTime());
                        done.put(id, future);
                        judge.convertAndSend(Question_EXCHANGE, QUESTION_DEBUG_ROUTING_KEY, uuid);
                        sample(future.get(3, TimeUnit.SECONDS));
                    } catch (Exception e) {
                        totalErrors.incrementAndGet();
                        starts.remove(id);
                        done.remove(id);
                    }
                }
            }));
        }
        int windowIndex = 0;
        long nextWindow = begin;
        while (System.nanoTime() < end) {
            Thread.sleep(30_000);
            windowIndex++;
            nextWindow += TimeUnit.SECONDS.toNanos(30);
            var sorted = new ArrayList<>(sampledLatencies);
            Collections.sort(sorted);
            Map<String, Object> window = new LinkedHashMap<>();
            window.put("window", windowIndex);
            window.put("tPlusSec", round((System.nanoTime() - begin) / 1e9));
            window.put("cumulativeAttempts", totalAttempts.get());
            window.put("cumulativeErrors", totalErrors.get());
            window.put("cumulativeErrorRatePct",
                    totalAttempts.get() == 0 ? 0 : round(totalErrors.get() * 100.0 / totalAttempts.get()));
            window.put("p95MsSoFar", pct(sorted, .95));
            window.put("p99MsSoFar", pct(sorted, .99));
            System.gc();
            window.put("heapUsedAfterGcMiB", round((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0));
            window.put("inFlight", inFlight.get());
            window.put("acks", acks.get());
            window.put("nacks", nacks.get());
            window.put("aborts", aborts.get());
            window.put("doubleSettlements", doubleSettlements.get());
            window.put("rabbitChannelCacheSize", cf.getChannelCacheSize());
            windows.add(window);
            System.err.println("SOAK " + json.writeValueAsString(window));
            sampledLatencies.clear();
        }
        for (var f : tasks) {
            f.get();
        }
        pool.shutdown();
        double elapsed = (System.nanoTime() - begin) / 1e9;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "fixed-jackson-callback-soak");
        result.put("seconds", round(elapsed));
        result.put("workers", workers);
        result.put("attempts", totalAttempts.get());
        result.put("errors", totalErrors.get());
        result.put("errorRatePct", totalAttempts.get() == 0 ? 0 : round(totalErrors.get() * 100.0 / totalAttempts.get()));
        result.put("qps", round(totalAttempts.get() / elapsed));
        System.gc();
        result.put("finalHeapUsedAfterGcMiB", round((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0));
        result.put("aborts", aborts.get());
        result.put("doubleSettlements", doubleSettlements.get());
        result.put("windows", windows);
        return result;
    }

    void setConverters(boolean jackson) {
        var converter = jackson ? new Jackson2JsonMessageConverter() : new SimpleMessageConverter();
        question.setMessageConverter(converter);
        judge.setMessageConverter(converter);
    }

    void sample(long nanos) {
        // 1/64 sampling: keeps percentiles meaningful without holding millions of longs
        if ((latencySamples.incrementAndGet() & 63) == 0) {
            sampledLatencies.add(nanos);
        }
    }

    static double pct(List<Long> a, double p) {
        return a.isEmpty() ? 0 : round(a.get(Math.min(a.size() - 1, (int) Math.ceil(a.size() * p) - 1)) / 1e6);
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static long cpu() {
        return ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
                .getOperatingSystemMXBean()).getProcessCpuTime();
    }

    /**
     * SIMULATED acceptance endpoint. Mirrors the production controller contract
     * (HTTP 200 + {@code Result{code,data,message}} where {@code data} is the task UUID)
     * but runs no Spring controller, gateway filter or STOMP session.
     */
    HttpServer http() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/api/question/debug", x -> {
            try {
                long id = sequence.incrementAndGet();
                String uuid = UUID.randomUUID().toString();
                DebugDto dto = new DebugDto();
                dto.setUserId(id);
                dto.setQuestionId(1L);
                dto.setCode("harness stubbed execution");
                dto.setLanguage("java");
                dto.setIncludeQuestionSample(false);
                GetDebugTestDto test = new GetDebugTestDto();
                test.setInput("1");
                test.setOutput("2");
                dto.setTests(new ArrayList<>(List.of(test)));
                starts.put(id, System.nanoTime());
                done.put(id, new CompletableFuture<>());
                redis.opsForHash().put(RedisContext.JUDGE_DEBUG_KEY, uuid, dto);
                question.convertAndSend(JUDGE_EXCHANGE, JUDGE_DEBUG_ROUTING_KEY, uuid);
                byte[] body = json.writeValueAsBytes(
                        Map.of("code", 200, "message", "success", "data", uuid));
                x.getResponseHeaders().add("Content-Type", "application/json");
                x.sendResponseHeaders(200, body.length);
                x.getResponseBody().write(body);
            } catch (Exception e) {
                x.sendResponseHeaders(500, -1);
            } finally {
                x.close();
            }
        });
        server.start();
        return server;
    }

    Map<String, Object> httpChain(int seconds, int workers) throws Exception {
        var server = http();
        int port = server.getAddress().getPort();
        Files.writeString(OUT.resolve("http-port"), String.valueOf(port));
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        var uri = URI.create("http://127.0.0.1:" + port + "/api/question/debug");
        long before = outputs.get();
        long failures = 0;
        long begin = System.nanoTime();
        long end = begin + TimeUnit.SECONDS.toNanos(seconds);
        var pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> tasks = new ArrayList<>();
        var accepted = new AtomicLong();
        for (int w = 0; w < workers; w++) {
            tasks.add(pool.submit(() -> {
                while (System.nanoTime() < end) {
                    try {
                        var request = java.net.http.HttpRequest.newBuilder(uri)
                                .timeout(java.time.Duration.ofSeconds(5))
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                                .build();
                        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() == 200 && response.body().contains("\"code\":200")) {
                            accepted.incrementAndGet();
                        }
                    } catch (Exception e) {
                        accepted.incrementAndGet();
                        // counted below via failures on request path only
                    }
                }
            }));
        }
        for (var f : tasks) {
            f.get();
        }
        pool.shutdown();
        // Drain: give the async chain time to finish accepted tasks.
        long drainUntil = System.currentTimeMillis() + 60_000;
        while (starts.size() > 0 && System.currentTimeMillis() < drainUntil) {
            Thread.sleep(200);
        }
        server.stop(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "simulated-http-acceptance-to-ws-result");
        result.put("boundary", "SIMULATED: no real controller/gateway/servlet/STOMP/browser");
        result.put("seconds", round((System.nanoTime() - begin) / 1e9));
        result.put("workers", workers);
        result.put("acceptedHttp200", accepted.get());
        result.put("acceptedQps", round(accepted.get() / ((System.nanoTime() - begin) / 1e9)));
        result.put("resultDelivered", outputs.get() - before);
        result.put("stillPending", starts.size());
        result.put("httpFailures", failures);
        result.put("aborts", aborts.get());
        result.put("doubleSettlements", doubleSettlements.get());
        return result;
    }

    static Path OUT;

    @Override
    public void close() {
        listeners.forEach(SimpleMessageListenerContainer::stop);
        cf.destroy();
        rcf.destroy();
        topology.close();
    }

    public static void main(String[] args) throws Exception {
        int rabbitPort = Integer.parseInt(args[0]);
        int redisPort = Integer.parseInt(args[1]);
        OUT = Path.of(args[2]);
        int soakSeconds = args.length > 3 ? Integer.parseInt(args[3]) : 1800;
        String mode = args.length > 4 ? args[4] : "full";
        Files.createDirectories(OUT);
        var h = new DebugJudgeHarness(rabbitPort, redisPort);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", RUN_ID);
        summary.put("startedAt", Instant.now().toString());
        summary.put("rabbitPort", rabbitPort);
        summary.put("redisPort", redisPort);
        try (h) {
            var meta = new LinkedHashMap<String, Object>();
            meta.put("note", "Real MqConfig topology, real RabbitTemplateConfig beans, real DebugHandle/Mq/"
                    + "JudgeDebugHandler/JudgeDebugConsumer. Stubbed only: judge DB question lookup and "
                    + "JudgeService.executeCode (no Docker judge container).");
            summary.put("harness", meta);
            h.integration();
            Files.writeString(OUT.resolve("integration.json"),
                    h.json.writerWithDefaultPrettyPrinter().writeValueAsString(h.checks));
            summary.put("integrationChecks", h.checks);
            summary.put("integrationAllPass", h.checks.stream()
                    .allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
            if ("soak".equals(mode)) {
                h.consumers();
                var soakOnly = h.soak(soakSeconds, 6);
                Files.writeString(OUT.resolve("soak.json"),
                        h.json.writerWithDefaultPrettyPrinter().writeValueAsString(soakOnly));
                summary.put("soak", soakOnly);
                summary.put("finishedAt", Instant.now().toString());
                summary.put("finalAcks", h.acks.get());
                summary.put("finalNacks", h.nacks.get());
                summary.put("finalAborts", h.aborts.get());
                summary.put("finalDoubleSettlements", h.doubleSettlements.get());
                Files.writeString(OUT.resolve("summary-soak.json"),
                        h.json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
                System.err.println("DONE " + OUT);
                return;
            }
            if ("integration".equals(mode)) {
                summary.put("mode", "integration-only");
                Files.writeString(OUT.resolve("summary.json"),
                        h.json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
                System.err.println("DONE " + OUT);
                return;
            }

            h.consumers();
            List<Map<String, Object>> loads = new ArrayList<>();
            loads.add(h.load("baseline-simple-DTO-publish", 3, 4, false, true));
            loads.add(h.load("fixed-jackson-DTO-publish", 5, 4, true, true));
            loads.add(h.load("baseline-simple-callback", 10, 4, false, false));
            loads.add(h.load("fixed-jackson-callback-warmup", 5, 4, true, false));
            loads.add(h.load("fixed-jackson-callback-sustained", 120, 8, true, false));
            loads.add(h.load("fixed-jackson-callback-spike", 30, 32, true, false));
            Files.writeString(OUT.resolve("mq-load.json"),
                    h.json.writerWithDefaultPrettyPrinter().writeValueAsString(loads));
            summary.put("mqLoad", loads);

            var httpChain = h.httpChain(45, 16);
            Files.writeString(OUT.resolve("http-chain.json"),
                    h.json.writerWithDefaultPrettyPrinter().writeValueAsString(httpChain));
            summary.put("httpChain", httpChain);

            var soak = h.soak(soakSeconds, 6);
            Files.writeString(OUT.resolve("soak.json"),
                    h.json.writerWithDefaultPrettyPrinter().writeValueAsString(soak));
            summary.put("soak", soak);

            summary.put("finishedAt", Instant.now().toString());
            summary.put("finalAcks", h.acks.get());
            summary.put("finalNacks", h.nacks.get());
            summary.put("finalAborts", h.aborts.get());
            summary.put("finalDoubleSettlements", h.doubleSettlements.get());
            Files.writeString(OUT.resolve("summary.json"),
                    h.json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        }
        System.err.println("DONE " + OUT);
        System.exit(0);
    }
}
