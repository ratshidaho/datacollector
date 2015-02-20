/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib.kafka;

import com.google.common.collect.ImmutableList;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Source;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.config.CsvMode;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.lib.json.StreamingJsonParser;
import com.streamsets.pipeline.sdk.SourceRunner;
import com.streamsets.pipeline.sdk.StageRunner;
import kafka.javaapi.producer.Producer;
import kafka.server.KafkaConfig;
import kafka.server.KafkaServer;
import kafka.utils.MockTime;
import kafka.utils.TestUtils;
import kafka.utils.TestZKUtils;
import kafka.utils.ZKStringSerializer$;
import kafka.zk.EmbeddedZookeeper;
import org.I0Itec.zkclient.ZkClient;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TestHighLevelKafkaSource {

  private static KafkaServer kafkaServer;
  private static ZkClient zkClient;
  private static EmbeddedZookeeper zkServer;
  private static int port;
  private static String zkConnect;

  private static Producer<String, String> producer;

  private static final String HOST = "localhost";
  private static final int BROKER_ID = 0;
  private static final int SINGLE_PARTITION = 1;
  private static final int MULTIPLE_PARTITIONS = 5;
  private static final int REPLICATION_FACTOR = 1;
  private static final String CONSUMER_GROUP = "SDC";
  private static final int TIME_OUT = 2000;

  @BeforeClass
  public static void setUp() {
    //Init zookeeper
    zkConnect = TestZKUtils.zookeeperConnect();
    zkServer = new EmbeddedZookeeper(zkConnect);
    zkClient = new ZkClient(zkServer.connectString(), 30000, 30000, ZKStringSerializer$.MODULE$);
    // setup Broker
    port = TestUtils.choosePort();
    Properties props = TestUtils.createBrokerConfig(BROKER_ID, port);
    List<KafkaServer> servers = new ArrayList<>();
    kafkaServer = TestUtils.createServer(new KafkaConfig(props), new MockTime());
    servers.add(kafkaServer);

    producer = KafkaTestUtil.createProducer(HOST, port);
  }

  @AfterClass
  public static void tearDown() {
    kafkaServer.shutdown();
    zkClient.close();
    zkServer.shutdown();
  }

  @Test
  public void testProduceStringRecords() throws StageException {

    CountDownLatch startLatch = new CountDownLatch(1);
    KafkaTestUtil.createTopic(zkClient, ImmutableList.of(kafkaServer), "testProduceStringRecords", SINGLE_PARTITION,
      REPLICATION_FACTOR, TIME_OUT);
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    executorService.submit(new ProducerRunnable( "testProduceStringRecords", SINGLE_PARTITION,
      producer, startLatch, DataType.LOG, null));

    SourceRunner sourceRunner = new SourceRunner.Builder(HighLevelKafkaSource.class)
      .addOutputLane("lane")
      .addConfiguration("topic", "testProduceStringRecords")
      .addConfiguration("consumerGroup", CONSUMER_GROUP)
      .addConfiguration("zookeeperConnect", zkConnect)
      .addConfiguration("maxBatchSize", 9)
      .addConfiguration("maxWaitTime", 5000)
      .addConfiguration("consumerPayloadType", DataFormat.TEXT)
      .addConfiguration("kafkaConsumerConfigs", null)
      .addConfiguration("produceSingleRecord", false)
      .build();

    sourceRunner.runInit();

    startLatch.countDown();
    StageRunner.Output output = sourceRunner.runProduce(null, 5);
    executorService.shutdown();

    String newOffset = output.getNewOffset();
    Assert.assertNull(newOffset);
    List<Record> records = output.getRecords().get("lane");
    Assert.assertEquals(5, records.size());

    for(int i = 0; i < records.size(); i++) {
      Assert.assertNotNull(records.get(i).get().getValueAsString());
      Assert.assertTrue(!records.get(i).get().getValueAsString().isEmpty());
      Assert.assertEquals(KafkaTestUtil.generateTestData(DataType.LOG, null), records.get(i).get().getValueAsString());
    }

    sourceRunner.runDestroy();
  }

  @Test
  public void testProduceStringRecordsMultiplePartitions() throws StageException {

    CountDownLatch startProducing = new CountDownLatch(1);
    KafkaTestUtil.createTopic(zkClient, ImmutableList.of(kafkaServer), "testProduceStringRecordsMultiplePartitions",
      MULTIPLE_PARTITIONS, REPLICATION_FACTOR, TIME_OUT);
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    executorService.submit(new ProducerRunnable( "testProduceStringRecordsMultiplePartitions",
      MULTIPLE_PARTITIONS, producer, startProducing, DataType.LOG, null));

    SourceRunner sourceRunner = new SourceRunner.Builder(HighLevelKafkaSource.class)
      .addOutputLane("lane")
      .addConfiguration("topic", "testProduceStringRecordsMultiplePartitions")
      .addConfiguration("consumerGroup", CONSUMER_GROUP)
      .addConfiguration("zookeeperConnect", zkConnect)
      .addConfiguration("maxBatchSize", 9)
      .addConfiguration("maxWaitTime", 5000)
      .addConfiguration("consumerPayloadType", DataFormat.TEXT)
      .addConfiguration("kafkaConsumerConfigs", null)
      .addConfiguration("produceSingleRecord", false)
      .build();

    sourceRunner.runInit();

    startProducing.countDown();
    StageRunner.Output output = sourceRunner.runProduce(null, 9);
    executorService.shutdown();

    String newOffset = output.getNewOffset();
    Assert.assertNull(newOffset);
    List<Record> records = output.getRecords().get("lane");
    Assert.assertEquals(9, records.size());

    for(int i = 0; i < records.size(); i++) {
      Assert.assertNotNull(records.get(i).get().getValueAsString());
      Assert.assertTrue(!records.get(i).get().getValueAsString().isEmpty());
      Assert.assertEquals(KafkaTestUtil.generateTestData(DataType.LOG, null), records.get(i).get().getValueAsString());
    }

    sourceRunner.runDestroy();
  }

  @Test
  public void testProduceJsonRecordsMultipleObjectsSingleRecord() throws StageException, IOException {

    CountDownLatch startLatch = new CountDownLatch(1);
    KafkaTestUtil.createTopic(zkClient, ImmutableList.of(kafkaServer),
      "testProduceJsonRecordsMultipleObjectsSingleRecord", SINGLE_PARTITION, REPLICATION_FACTOR, TIME_OUT);
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    executorService.submit(new ProducerRunnable( "testProduceJsonRecordsMultipleObjectsSingleRecord", SINGLE_PARTITION,
      producer, startLatch, DataType.JSON, StreamingJsonParser.Mode.MULTIPLE_OBJECTS));

    SourceRunner sourceRunner = new SourceRunner.Builder(HighLevelKafkaSource.class)
      .addOutputLane("lane")
      .addConfiguration("topic", "testProduceJsonRecordsMultipleObjectsSingleRecord")
      .addConfiguration("consumerGroup", CONSUMER_GROUP)
      .addConfiguration("zookeeperConnect", zkConnect)
      .addConfiguration("maxBatchSize", 9)
      .addConfiguration("maxWaitTime", 5000)
      .addConfiguration("consumerPayloadType", DataFormat.JSON)
      .addConfiguration("jsonContent", StreamingJsonParser.Mode.MULTIPLE_OBJECTS)
      .addConfiguration("produceSingleRecord", true)
      .addConfiguration("maxJsonObjectLen", 4096)
      .addConfiguration("kafkaConsumerConfigs", null)
      .build();

    sourceRunner.runInit();

    startLatch.countDown();
    StageRunner.Output output = sourceRunner.runProduce(null, 9);
    executorService.shutdown();

    String newOffset = output.getNewOffset();
    Assert.assertNull(newOffset);

    List<Record> records = output.getRecords().get("lane");
    Assert.assertEquals(9, records.size());

    JsonRecordCreator jsonFieldCreator = new JsonRecordCreator((Source.Context)sourceRunner.getContext(),
      StreamingJsonParser.Mode.MULTIPLE_OBJECTS, 4096, true, "testProduceJsonRecordsMultipleObjectsSingleRecord");
    for(int i = 0; i < records.size(); i++) {
      //Multiple json objects into one record generates a field of type list
      Assert.assertNotNull(records.get(i).get().getValueAsList());
      Assert.assertTrue(!records.get(i).get().getValueAsList().isEmpty());

      MessageAndOffset messageAndOffset = new MessageAndOffset(KafkaTestUtil.generateTestData(
        DataType.JSON, StreamingJsonParser.Mode.MULTIPLE_OBJECTS).getBytes(), 10, 0);
      List<Record> expectedRecords = jsonFieldCreator.createRecords(messageAndOffset, 5);
      Assert.assertEquals(1, expectedRecords.size());

      Assert.assertEquals(expectedRecords.get(0).get().getValueAsList().size(),
        records.get(i).get().getValueAsList().size());
      for(int j = 0; j< expectedRecords.get(0).get().getValueAsList().size(); j++) {
        Assert.assertEquals(expectedRecords.get(0).get().getValueAsList().get(j).getValueAsMap(),
          records.get(i).get().getValueAsList().get(j).getValueAsMap());
      }
    }
    sourceRunner.runDestroy();
  }

  @Test
  public void testProduceJsonRecordsMultipleObjectsMultipleRecord() throws StageException, IOException {

    CountDownLatch startLatch = new CountDownLatch(1);
    KafkaTestUtil.createTopic(zkClient, ImmutableList.of(kafkaServer), "testProduceJsonRecordsMultipleObjectsMultipleRecord", SINGLE_PARTITION,
      REPLICATION_FACTOR, TIME_OUT);
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    executorService.submit(new ProducerRunnable( "testProduceJsonRecordsMultipleObjectsMultipleRecord", SINGLE_PARTITION,
      producer, startLatch, DataType.JSON, StreamingJsonParser.Mode.MULTIPLE_OBJECTS));

    SourceRunner sourceRunner = new SourceRunner.Builder(HighLevelKafkaSource.class)
      .addOutputLane("lane")
      .addConfiguration("topic", "testProduceJsonRecordsMultipleObjectsMultipleRecord")
      .addConfiguration("consumerGroup", CONSUMER_GROUP)
      .addConfiguration("zookeeperConnect", zkConnect)
      .addConfiguration("maxBatchSize", 9)
      .addConfiguration("maxWaitTime", 5000)
      .addConfiguration("consumerPayloadType", DataFormat.JSON)
      .addConfiguration("jsonContent", StreamingJsonParser.Mode.MULTIPLE_OBJECTS)
      .addConfiguration("produceSingleRecord", false)
      .addConfiguration("maxJsonObjectLen", 4096)
      .addConfiguration("kafkaConsumerConfigs", null)
      .build();

    sourceRunner.runInit();

    startLatch.countDown();
    StageRunner.Output output = sourceRunner.runProduce(null, 12);
    executorService.shutdown();

    String newOffset = output.getNewOffset();
    Assert.assertNull(newOffset);

    List<Record> records = output.getRecords().get("lane");
    Assert.assertEquals(12, records.size());

    JsonRecordCreator jsonFieldCreator = new JsonRecordCreator((Source.Context)sourceRunner.getContext(),
      StreamingJsonParser.Mode.MULTIPLE_OBJECTS, 4096, false, "testProduceJsonRecordsMultipleObjectsMultipleRecord");
    for(int i = 0; i < records.size(); i+=4) {
      Assert.assertNotNull(records.get(i).get().getValueAsMap());
      Assert.assertTrue(!records.get(i).get().getValueAsMap().isEmpty());
      for(int j = 0; j < 3; j++) {
        MessageAndOffset messageAndOffset = new MessageAndOffset(KafkaTestUtil.generateTestData(
          DataType.JSON, StreamingJsonParser.Mode.MULTIPLE_OBJECTS).getBytes(), 10, 0);
        List<Record> expectedRecords = jsonFieldCreator.createRecords(messageAndOffset, 5);
        Assert.assertEquals(4, expectedRecords.size());
        Assert.assertEquals(expectedRecords.get(j).get().getValueAsMap(),
          records.get(i+j).get().getValueAsMap());
      }
    }
    sourceRunner.runDestroy();
  }

  @Test
  public void testProduceJsonRecordsArrayObjects() throws StageException, IOException {

    CountDownLatch startLatch = new CountDownLatch(1);
    KafkaTestUtil.createTopic(zkClient, ImmutableList.of(kafkaServer), "testProduceJsonRecordsArrayObjects", SINGLE_PARTITION,
      REPLICATION_FACTOR, TIME_OUT);
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    executorService.submit(new ProducerRunnable( "testProduceJsonRecordsArrayObjects", SINGLE_PARTITION,
      producer, startLatch, DataType.JSON, StreamingJsonParser.Mode.ARRAY_OBJECTS));

    SourceRunner sourceRunner = new SourceRunner.Builder(HighLevelKafkaSource.class)
      .addOutputLane("lane")
      .addConfiguration("topic", "testProduceJsonRecordsArrayObjects")
      .addConfiguration("consumerGroup", CONSUMER_GROUP)
      .addConfiguration("zookeeperConnect", zkConnect)
      .addConfiguration("maxBatchSize", 9)
      .addConfiguration("maxWaitTime", 5000)
      .addConfiguration("consumerPayloadType", DataFormat.JSON)
      .addConfiguration("jsonContent", StreamingJsonParser.Mode.ARRAY_OBJECTS)
      .addConfiguration("maxJsonObjectLen", 4096)
      .addConfiguration("kafkaConsumerConfigs", null)
      .addConfiguration("produceSingleRecord", true)
      .build();

    sourceRunner.runInit();

    startLatch.countDown();
    StageRunner.Output output = sourceRunner.runProduce(null, 9);
    executorService.shutdown();

    String newOffset = output.getNewOffset();
    Assert.assertNull(newOffset);

    List<Record> records = output.getRecords().get("lane");
    Assert.assertEquals(9, records.size());

    JsonRecordCreator jsonFieldCreator = new JsonRecordCreator((Source.Context)sourceRunner.getContext(),
      StreamingJsonParser.Mode.ARRAY_OBJECTS, 4096, false, "testProduceJsonRecordsArrayObjects");
    for(int i = 0; i < records.size(); i++) {
      //Array of json objects into one record generates a field of type list
      Assert.assertNotNull(records.get(i).get().getValueAsList());
      Assert.assertTrue(!records.get(i).get().getValueAsList().isEmpty());

      MessageAndOffset messageAndOffset = new MessageAndOffset(KafkaTestUtil.generateTestData(
        DataType.JSON, StreamingJsonParser.Mode.ARRAY_OBJECTS).getBytes(), 10, 0);
      List<Record> expectedRecords = jsonFieldCreator.createRecords(messageAndOffset, 5);
      Assert.assertEquals(1, expectedRecords.size());

      Assert.assertEquals(expectedRecords.get(0).get().getValueAsList().size(),
        records.get(i).get().getValueAsList().size());
      for(int j = 0; j< expectedRecords.get(0).get().getValueAsList().size(); j++) {
        Assert.assertEquals(expectedRecords.get(0).get().getValueAsList().get(j).getValueAsMap(),
          records.get(i).get().getValueAsList().get(j).getValueAsMap());
      }
    }
    sourceRunner.runDestroy();
  }

  @Test
  public void testProduceXmlRecords() throws StageException, IOException {

    CountDownLatch startLatch = new CountDownLatch(1);
    KafkaTestUtil.createTopic(zkClient, ImmutableList.of(kafkaServer), "testProduceXmlRecords", SINGLE_PARTITION,
      REPLICATION_FACTOR, TIME_OUT);
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    executorService.submit(new ProducerRunnable( "testProduceXmlRecords", SINGLE_PARTITION,
      producer, startLatch, DataType.XML, null));

    SourceRunner sourceRunner = new SourceRunner.Builder(HighLevelKafkaSource.class)
      .addOutputLane("lane")
      .addConfiguration("topic", "testProduceXmlRecords")
      .addConfiguration("consumerGroup", CONSUMER_GROUP)
      .addConfiguration("zookeeperConnect", zkConnect)
      .addConfiguration("maxBatchSize", 9)
      .addConfiguration("maxWaitTime", 5000)
      .addConfiguration("consumerPayloadType", DataFormat.XML)
      .addConfiguration("jsonContent", null)
      .addConfiguration("maxJsonObjectLen", null)
      .addConfiguration("kafkaConsumerConfigs", null)
      .addConfiguration("produceSingleRecord", true)
      .build();

    sourceRunner.runInit();

    startLatch.countDown();
    StageRunner.Output output = sourceRunner.runProduce(null, 9);
    executorService.shutdown();

    String newOffset = output.getNewOffset();
    Assert.assertNull(newOffset);

    List<Record> records = output.getRecords().get("lane");
    Assert.assertEquals(9, records.size());

    XmlRecordCreator xmlFieldCreator = new XmlRecordCreator((Source.Context)sourceRunner.getContext(),
      "testProduceXmlRecords");
    for(int i = 0; i < records.size(); i++) {
      Assert.assertNotNull(records.get(i).get().getValueAsMap());
      Assert.assertTrue(!records.get(i).get().getValueAsMap().isEmpty());
      List<Record> expectedRecords = xmlFieldCreator.createRecords(
        new MessageAndOffset(KafkaTestUtil.generateTestData(DataType.XML, null).getBytes(), 10, 0), 0);
      Assert.assertEquals(expectedRecords.get(0).get().getValueAsMap(), records.get(i).get().getValueAsMap());
    }
    sourceRunner.runDestroy();

  }

  @Test
  public void testProduceCsvRecords() throws StageException, IOException {
    CountDownLatch startLatch = new CountDownLatch(1);
    KafkaTestUtil.createTopic(zkClient, ImmutableList.of(kafkaServer), "testProduceCsvRecords", SINGLE_PARTITION,
      REPLICATION_FACTOR, TIME_OUT);
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    executorService.submit(new ProducerRunnable( "testProduceCsvRecords", SINGLE_PARTITION,
      producer, startLatch, DataType.CSV, null));

    SourceRunner sourceRunner = new SourceRunner.Builder(HighLevelKafkaSource.class)
      .addOutputLane("lane")
      .addConfiguration("topic", "testProduceCsvRecords")
      .addConfiguration("consumerGroup", CONSUMER_GROUP)
      .addConfiguration("zookeeperConnect", zkConnect)
      .addConfiguration("maxBatchSize", 9)
      .addConfiguration("maxWaitTime", 5000)
      .addConfiguration("consumerPayloadType", DataFormat.DELIMITED)
      .addConfiguration("csvFileFormat", CsvMode.CSV)
      .addConfiguration("kafkaConsumerConfigs", null)
      .addConfiguration("produceSingleRecord", true)
      .build();

    sourceRunner.runInit();

    startLatch.countDown();
    StageRunner.Output output = sourceRunner.runProduce(null, 9);
    executorService.shutdown();

    String newOffset = output.getNewOffset();
    Assert.assertNull(newOffset);
    List<Record> records = output.getRecords().get("lane");
    Assert.assertEquals(9, records.size());

    CsvRecordCreator csvFieldCreator = new CsvRecordCreator((Source.Context)sourceRunner.getContext(), CsvMode.CSV,
      "testProduceCsvRecords");
    for (int i = 0; i < records.size(); i++) {
      Assert.assertNotNull(records.get(i).get().getValueAsMap());
      Assert.assertTrue(!records.get(i).get().getValueAsMap().isEmpty());
      List<Record> expectedRecords = csvFieldCreator.createRecords(
        new MessageAndOffset(KafkaTestUtil.generateTestData(DataType.CSV, null).getBytes(), 10, 0), 0);
      Assert.assertEquals(expectedRecords.get(0).get().getValueAsMap(), records.get(i).get().getValueAsMap());
    }
    sourceRunner.runDestroy();
  }
}
