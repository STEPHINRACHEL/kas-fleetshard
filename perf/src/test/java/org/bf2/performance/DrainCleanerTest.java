package org.bf2.performance;

import io.fabric8.kubernetes.api.model.Quantity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacity;
import org.bf2.performance.framework.KubeClusterResource;
import org.bf2.performance.framework.TestTags;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Tag(TestTags.PERF)
public class DrainCleanerTest extends TestBase {
    private static final Logger LOGGER = LogManager.getLogger(DrainCleanerTest.class);
    private static final Quantity WORKER_SIZE = Quantity.parse("2Gi");

    private KafkaProvisioner kafkaProvisioner;
    private List<String> workers;
    private OMB omb;

    @BeforeAll
    void beforeAll() throws Exception {
        kafkaProvisioner = KafkaProvisioner.create(KubeClusterResource.connectToKubeCluster(Environment.KAFKA_KUBECONFIG));
        kafkaProvisioner.setup();
        omb = new OMB(KubeClusterResource.connectToKubeCluster(Environment.OMB_KUBECONFIG));
        omb.install();
        omb.setWorkerContainerMemory(WORKER_SIZE);
    }

    @AfterAll
    void afterAll() throws Exception {
        omb.uninstall();
        kafkaProvisioner.teardown();
    }

    @BeforeEach
    void beforeEach() throws Exception {
        kafkaProvisioner.install();
    }

    @AfterEach
    void afterEach() throws Exception {
        kafkaProvisioner.uninstall();
        omb.deleteWorkers();
    }

    @Test
    @Tag(TestTags.PERF)
    void testKafkaAvailabilityDuringClusterUpgrade(TestInfo info) throws Exception {
        long throughput = 41943040;
        int numWorkers = 20;
        int topics = 10;
        int messageSize = 1024;
        int partitionsPerTopic = 205;
        int workerProducerRate = 40000;

        ensureClientClusterCapacityForWorkers(omb.getOmbCluster(), numWorkers, WORKER_SIZE);

        workers = omb.deployWorkers(numWorkers);

        ManagedKafkaCapacity capacity = KafkaConfigurations.defaultCapacity(throughput);
        ManagedKafka kafka = KafkaConfigurations.apply(capacity, "cluster1").build();
        String bootstrapHosts = kafkaProvisioner.deployCluster(kafka, AdopterProfile.VALUE_PROD).waitUntilReady();

        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        Future<Integer> nodeDrain;
        Future<File> resultDone;
        try {
            nodeDrain = scheduler.schedule(() -> {

                // this thread simulates the OpenShift cluster upgrade
                LOGGER.info("PERFORMING SCHEDULED NODES DRAIN");
                kafkaProvisioner.getKubernetesCluster().kubeClient().getClusterWorkers().forEach(node -> {
                    TestUtils.drainNode(kafkaProvisioner.getKubernetesCluster(), node.getMetadata().getName());
                    TestUtils.waitUntilAllPodsReady(kafkaProvisioner.getKubernetesCluster(), kafka.getMetadata().getNamespace());
                    TestUtils.setNodeSchedule(kafkaProvisioner.getKubernetesCluster(), node.getMetadata().getName(), true);
                });

                return 0;
            }, 2, TimeUnit.MINUTES);

            resultDone = scheduler.submit(() -> {
                OMBDriver driver = new OMBDriver()
                        .setReplicationFactor(3)
                        .setTopicConfig("min.insync.replicas=2\n")
                        .setCommonConfig(String.format("bootstrap.servers=%s\nsecurity.protocol=SSL\nssl.truststore.password=testing\nssl.truststore.type=JKS\nssl.truststore.location=/cert/ca.jks\n", bootstrapHosts))
                        .setProducerConfig("acks=all\n")
                        .setConsumerConfig("auto.offset.reset=earliest\nenable.auto.commit=false\n");

                int producerConsumer = numWorkers / 2;
                LOGGER.info("PERFORMING OMB WORKLOAD");
                OMBWorkloadResult result = omb.runWorkload(instanceDir, driver, workers, new OMBWorkload()
                        .setName(info.getDisplayName())
                        .setTopics(topics)
                        .setPartitionsPerTopic(partitionsPerTopic)
                        .setMessageSize(messageSize)
                        .setPayloadFile("src/test/resources/payload/payload-1Kb.data")
                        .setSubscriptionsPerTopic(1)
                        .setConsumerPerSubscription(producerConsumer)
                        .setProducersPerTopic(producerConsumer)
                        .setProducerRate(workerProducerRate)
                        .setConsumerBacklogSizeGB(0)
                        .setTestDurationMinutes(15));
                LOGGER.info("{}: results {}", info.getDisplayName(), result.getResultFile());
                LOGGER.info("COMPLETED OMB WORKLOAD");
                return result.getResultFile();
            });

            Integer podTaskRetVal = nodeDrain.get(25, TimeUnit.MINUTES);
            LOGGER.info("Node drain task return value: {}", podTaskRetVal.toString());
            File resultFile = resultDone.get(25, TimeUnit.MINUTES);
            LOGGER.info("Result file: {}", resultFile);
        } finally {
            scheduler.shutdown();
            scheduler.awaitTermination(1, TimeUnit.MINUTES);
        }
    }
}