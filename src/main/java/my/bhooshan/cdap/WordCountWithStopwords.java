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
import co.cask.cdap.api.app.AbstractApplication;
import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.stream.StreamBatchReadable;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.api.flow.flowlet.StreamEvent;
import co.cask.cdap.api.mapreduce.AbstractMapReduce;
import co.cask.cdap.api.mapreduce.MapReduceContext;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
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

  public static final String MR_OUTPUT_DATASET = "output";
  public static final String STOPWORDS_FILE_ARG = "stopwords.file";

  @Override
  public void configure() {
    createDataset(MR_OUTPUT_DATASET, KeyValueTable.class);
    addStream("LocalFileStream");
    addMapReduce(new MapReduceWithLocalFiles());
  }

  public static class MapReduceWithLocalFiles extends AbstractMapReduce {

    @Override
    public void beforeSubmit(MapReduceContext context) throws Exception {
      Map<String, String> args = context.getRuntimeArguments();
      if (args.containsKey(STOPWORDS_FILE_ARG)) {
        context.addLocalFile(URI.create(args.get(STOPWORDS_FILE_ARG)));
      }
      context.setInput(new StreamBatchReadable("LocalFileStream"));
      context.addOutput(args.get(MR_OUTPUT_DATASET));
      Job job = context.getHadoopJob();
      job.setMapperClass(TokenizerMapper.class);
      job.setReducerClass(IntSumReducer.class);
    }

    public static class TokenizerMapper extends Mapper<LongWritable, StreamEvent, Text, IntWritable>
      implements ProgramLifecycle<MapReduceContext> {

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
      public void initialize(MapReduceContext context) throws Exception {
        List<URI> localFiles = context.getLocalFiles();
        Map<String, String> args = context.getRuntimeArguments();
        if (args.containsKey(STOPWORDS_FILE_ARG)) {
          String localFilePath = args.get(STOPWORDS_FILE_ARG);
          for (URI localFile : localFiles) {
            if (localFilePath.equals(localFile.toString())) {
              try (FileSystem fs = FileSystem.get(localFile, new Configuration());
                   FSDataInputStream fds = fs.open(new org.apache.hadoop.fs.Path(localFile));
                   InputStreamReader isr = new InputStreamReader(fds);
                   BufferedReader reader = new BufferedReader(isr)) {
                String line;
                while ((line = reader.readLine()) != null) {
                  stopWords.add(line);
                }
              }
            }
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
}
