/*
 * Copyright © 2014 Cask Data, Inc.
 *
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

import co.cask.cdap.test.TestBase;
import co.cask.cdap.test.TestConfiguration;
import com.google.gson.Gson;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;

/**
 * Test for {@link WordCountWithStopwords}.
 */
public class WordCountWithStopwordsTest {

  @Test
  public void test() throws IOException {
    URI uri = URI.create("file:///path/to/file.txt");
    System.out.println(uri.getPath());
    Job job = Job.getInstance();
    job.addCacheArchive(
      URI.create("file:///Users/bhooshanmogal/Documents/tmpcdap/cdap/cdap-examples/Purchase/" +
                   "target/Purchase-3.3.0-SNAPSHOT.jar#Purchase.jar"));
    job.addCacheFile(
      URI.create("file:///Users/bhooshanmogal/Documents/tmpcdap/cdap/cdap-examples/Purchase/" +
                   "resources/purchases.txt#purchases.txt")
    );

    for (URI archive : job.getCacheArchives()) {
      System.out.println("archive = " + archive);
    }

    for (URI file : job.getCacheFiles()) {
      System.out.println("file = " + file);
    }

    Configuration conf = new Configuration();
    DistributedCache.addCacheArchive(
      URI.create("file:///Users/bhooshanmogal/Documents/tmpcdap/cdap/cdap-examples/Purchase/" +
                   "target/Purchase-3.3.0-SNAPSHOT.jar#Purchase.jar"),
      conf);

    for (URI path : DistributedCache.getCacheArchives(conf)) {
      System.out.println("path = " + path);
    }

    DistributedCache.addCacheFile(
      URI.create("file:///Users/bhooshanmogal/Documents/tmpcdap/cdap/cdap-examples/Purchase/" +
                   "resources/purchases.txt#purchases.txt"),
      conf
    );
    for (URI file : DistributedCache.getCacheFiles(conf)) {
      System.out.println("file = " + file);
    }

    File file = new File("/Users/bhooshanmogal/work/git/cdap/pom.xml");
    Gson gson = new Gson();
    String string = gson.toJson(file);
    System.out.println("######File = " + string);
    File file1 = gson.fromJson(string, File.class);
  }


}
