package com.example.demo;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.KTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.atilika.kuromoji.ipadic.Tokenizer;
import com.example.demo.TopFive.Item;

import io.confluent.examples.streams.utils.GenericAvroSerde;

@Component
public class TweetWordCounter {

  private static final Logger logger = LoggerFactory.getLogger(TweetWordCounter.class);

  private static final Tokenizer tokenizer = new Tokenizer();

  @Value(value = "classpath:avro/top5-schema.avsc")
  private Resource top5SchemaFile;

  @Autowired
  private StreamsConfig kStreamConfig;

  @SuppressWarnings("resource")
  @Bean
  public KStream<String, GenericRecord> kStream(KStreamBuilder kStreamBuilder) throws Exception {
    KStream<String, GenericRecord> stream = kStreamBuilder.stream("Tweets");

    // @formatter:off
    KTable<String, Long> tweetWordCounts = stream
      .map((key, tweet) -> {
        if ("Tweet".equals(tweet.getSchema().getName())) {
          String text = ((Utf8) tweet.get("text")).toString();
          logger.info("[Tweet]Map to string. " + text);
          return new KeyValue<String, String>(key, text);
        } else {
          String text = ((Utf8) tweet.get("text2")).toString();
          logger.info("[Tweet2]Map to string. " + text);
          return new KeyValue<String, String>(key, text);
        }
      })
      .flatMapValues(value -> {
        List<String> tokens = tokenize(value);
        logger.info("Flatmap to tokens. " + tokens);
        return tokens;
      })
      .groupBy((key, word) -> word, Serdes.String(), Serdes.String())
      .count("TweetWordCounts");
    
    // Create Top5
    GenericAvroSerde genericAvroSerde = (GenericAvroSerde)kStreamConfig.valueSerde();
    Schema top5Schema;
    try (InputStream is = top5SchemaFile.getInputStream()) {
      top5Schema = new Schema.Parser().parse(is);
    }
    tweetWordCounts
        .groupBy((word, count) -> {
            return KeyValue.pair("top5", word + " " + count);
          }, Serdes.String(), Serdes.String())
        .aggregate(
          () -> new GenericData.Record(top5Schema),
          (aggKey, value, aggregate) -> {
            return TopFive.from(aggregate).add(new Item(value)).to(top5Schema);
          },
          (aggKey, value, aggregate) -> {
            return TopFive.from(aggregate).remove(new Item(value)).to(top5Schema);
          },
          genericAvroSerde,
          "TweetWordTop5"
        );
    // @formatter:on
    return stream;
  }

  private List<String> tokenize(String value) {
    // @formatter:off
    return tokenizer.tokenize(value).stream()
      // 辞書にあって名詞で2文字以上のにしてみた
      .filter(token -> token.isKnown() && token.getPartOfSpeechLevel1().equals("名詞") && token.getBaseForm().length() >= 2)
      .map(token -> token.getBaseForm())
      .collect(Collectors.toList());
    // @formatter:on
  }

}
