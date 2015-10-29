/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package my.bhooshan.cdap;

import co.cask.cdap.api.ProgramLifecycle;
import co.cask.cdap.api.Resources;
import co.cask.cdap.api.TaskLocalizationContext;
import co.cask.cdap.api.app.AbstractApplication;
import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.stream.StreamBatchReadable;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.api.flow.flowlet.StreamEvent;
import co.cask.cdap.api.mapreduce.AbstractMapReduce;
import co.cask.cdap.api.mapreduce.MapReduceContext;
import co.cask.cdap.api.mapreduce.MapReduceTaskContext;
import co.cask.cdap.api.spark.AbstractSpark;
import co.cask.cdap.api.spark.JavaSparkProgram;
import co.cask.cdap.api.spark.SparkContext;
import co.cask.cdap.common.io.Locations;
import co.cask.cdap.common.lang.jar.BundleJarUtil;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.PairFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * This is a simple HelloWorld example that uses one stream, one dataset, one flow and one service.
 * <uL>
 *   <li>A stream to send names to.</li>
 *   <li>A flow with a single flowlet that reads the stream and stores each name in a KeyValueTable</li>
 *   <li>A service that reads the name from the KeyValueTable and responds with 'Hello [Name]!'</li>
 * </uL>
 */
public class WordCountWithStopwords extends AbstractApplication {
  private static final Logger LOG = LoggerFactory.getLogger(WordCountWithStopwords.class);
  private static final String MR_INPUT_STREAM = "LocalFileStream";
  private static final String MR_OUTPUT_DATASET = "mr_output";
  private static final String STOPWORDS_FILE_ARG = "stopwords.file";
  private static final String STOPWORDS_ALIAS = "stopwords";
  private static final String SPARK_OUTPUT_DATASET = "spark_output";
  private static final String LOCAL_FILE_RUNTIME_ARG = "local.file";
  private static final String LOCAL_ARCHIVE_ALIAS = "archive.jar";

  @Override
  public void configure() {
    createDataset(MR_OUTPUT_DATASET, KeyValueTable.class);
    createDataset(SPARK_OUTPUT_DATASET, KeyValueTable.class);
    addStream(MR_INPUT_STREAM);
    addMapReduce(new MapReduceWithLocalFiles());
    addSpark(new JavaSparkUsingLocalFiles());
  }

  public static class MapReduceWithLocalFiles extends AbstractMapReduce {

    @Override
    public void beforeSubmit(MapReduceContext context) throws Exception {
      Map<String, String> args = context.getRuntimeArguments();
      if (args.containsKey(STOPWORDS_FILE_ARG)) {
        context.localize(STOPWORDS_ALIAS, URI.create(args.get(STOPWORDS_FILE_ARG)));
      }
      context.setInput(new StreamBatchReadable(MR_INPUT_STREAM));
      context.addOutput(MR_OUTPUT_DATASET);
      Job job = context.getHadoopJob();
      job.setMapperClass(TokenizerMapper.class);
      job.setReducerClass(IntSumReducer.class);
    }

    public static class TokenizerMapper extends Mapper<LongWritable, StreamEvent, Text, IntWritable>
      implements ProgramLifecycle<MapReduceTaskContext> {

      private static final IntWritable ONE = new IntWritable(1);
      private Text word = new Text();
      private final List<String> stopWords = new ArrayList<>();

      @Override
      public void map(LongWritable key, StreamEvent value, Context context) throws IOException, InterruptedException {
        StringTokenizer itr = new StringTokenizer(Bytes.toString(value.getBody()));

        while (itr.hasMoreTokens()) {
          String token = itr.nextToken();
          if (!stopWords.contains(token)) {
            word.set(token);
            context.write(word, ONE);
          }
        }
      }

      @Override
      public void initialize(MapReduceTaskContext context) throws Exception {
        Map<String, File> localFiles = context.getAllLocalFiles();
        Preconditions.checkState(localFiles.containsKey(STOPWORDS_ALIAS));
        Map<String, String> args = context.getRuntimeArguments();
        Preconditions.checkState(args.containsKey(STOPWORDS_FILE_ARG));
        try (BufferedReader reader = Files.newBufferedReader(context.getLocalFile(STOPWORDS_ALIAS).toPath(),
                                                             Charsets.UTF_8)) {
          String line;
          while ((line = reader.readLine()) != null) {
            stopWords.add(line);
          }
        }
      }

      @Override
      public void destroy() {
      }
    }

    /**
     *
     */
    public static class IntSumReducer extends Reducer<Text, IntWritable, byte[], byte[]> {
      @Override
      public void reduce(Text key, Iterable<IntWritable> values, Context context)
        throws IOException, InterruptedException {
        int sum = 0;
        for (IntWritable val : values) {
          sum += val.get();
        }
        context.write(Bytes.toBytes(key.toString()), Bytes.toBytes(sum));
      }
    }
  }

  public static class JavaSparkUsingLocalFiles extends AbstractSpark {
    @Override
    protected void configure() {
      setMainClass(JavaSparkProgramUsingLocalFiles.class);
      setDriverResources(new Resources(1024));
      setExecutorResources(new Resources(1024));
    }

    @Override
    public void beforeSubmit(SparkContext context) throws Exception {
      Map<String, String> args = context.getRuntimeArguments();
      String localFilePath = args.get(LOCAL_FILE_RUNTIME_ARG);
      Preconditions.checkArgument(localFilePath != null, "Runtime argument %s must be set.", LOCAL_FILE_RUNTIME_ARG);
      context.localize(URI.create(localFilePath));
      context.localize(LOCAL_ARCHIVE_ALIAS, createTemporaryArchiveFile(), true);
    }

    private URI createTemporaryArchiveFile() throws IOException {
      File tmpDir1 = com.google.common.io.Files.createTempDir();
      List<File> files = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        File tmpFile = File.createTempFile("abcd" + i, "txt", tmpDir1);
        files.add(tmpFile);
      }

      File tmpDir2 = com.google.common.io.Files.createTempDir();
      File destArchive = new File(tmpDir2, "myBundle.jar");
      BundleJarUtil.createJar(tmpDir1, destArchive);
      for (File file : files) {
        BundleJarUtil.getEntry(Locations.toLocation(destArchive), file.getName()).getInput().close();
      }
      return destArchive.toURI();
    }
  }

  public static class JavaSparkProgramUsingLocalFiles implements JavaSparkProgram {

    @Override
    public void run(SparkContext context) {
      Map<String, String> args = context.getRuntimeArguments();
      Preconditions.checkArgument(args.containsKey(LOCAL_FILE_RUNTIME_ARG),
                                  "Runtime argument %s must be set.", LOCAL_FILE_RUNTIME_ARG);
      String localFilePath = URI.create(args.get(LOCAL_FILE_RUNTIME_ARG)).getPath();
      final String localFileName = localFilePath.substring(localFilePath.lastIndexOf(Path.SEPARATOR) + 1);
      final TaskLocalizationContext localizationContext = context.getTaskLocalizationContext();
      Map<String, File> localFiles;
      try {
        localFiles = localizationContext.getAllLocalFiles();
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
      boolean localFileFound = false;
      for (File localFile : localFiles.values()) {
        LOG.error("######################## localFilePath={}, localFileName={}, localFile={}",
                  localFilePath, localFileName, localFile);
        if (localFileName.equals(localFile.toString())) {
          localFileFound = true;
          break;
        }
      }
      Preconditions.checkState(localFileFound, "Local file %s must be found.", localFilePath);
      JavaSparkContext sc = context.getOriginalSparkContext();
      JavaRDD<String> fileContents = sc.textFile(localFilePath, 1);
      JavaPairRDD<byte[], byte[]> rows = fileContents.mapToPair(new PairFunction<String, byte[], byte[]>() {
        @Override
        public Tuple2<byte[], byte[]> call(String line) throws Exception {
          File localFile = localizationContext.getLocalFile(localFileName);
          Preconditions.checkState(localFile.exists(), "Local file %s must exist.", localFile);
          File localArchive = localizationContext.getLocalFile(LOCAL_ARCHIVE_ALIAS, true);
          Preconditions.checkState(localArchive.exists(), "Local archive %s must exist.", LOCAL_ARCHIVE_ALIAS);
          Iterator<String> splitter = Splitter.on("=").omitEmptyStrings().trimResults().split(line).iterator();
          Preconditions.checkArgument(splitter.hasNext());
          String key = splitter.next();
          Preconditions.checkArgument(splitter.hasNext());
          String value = splitter.next();
          return new Tuple2<>(Bytes.toBytes(key), Bytes.toBytes(value));
        }
      });

      context.writeToDataset(rows, SPARK_OUTPUT_DATASET, byte[].class, byte[].class);
    }
  }
}
