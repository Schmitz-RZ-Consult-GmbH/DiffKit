/**
 * Copyright 2010 Joseph Panico
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.diffkit.diff.conf;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.util.Arrays;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractXmlApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;

import org.diffkit.common.DKDistProperties;
import org.diffkit.common.DKUserException;
import org.diffkit.diff.engine.DKContext;
import org.diffkit.diff.engine.DKDiffEngine;
import org.diffkit.diff.engine.DKSink;
import org.diffkit.diff.engine.DKSource;
import org.diffkit.diff.engine.DKTableComparison;
import org.diffkit.util.DKClassUtil;

/**
 * @author jpanico
 */
public class DKApplication {
   private static final String VERSION_OPTION_KEY = "version";
   private static final String HELP_OPTION_KEY = "help";
   private static final String TEST_OPTION_KEY = "test";
   private static final String PLAN_FILE_OPTION_KEY = "planfiles";
   private static final String ERROR_ON_DIFF_OPTION_KEY = "errorOnDiff";
   private static final Options OPTIONS = new Options();

   private static final Logger LOG = LoggerFactory.getLogger(DKApplication.class);
   private static final Logger USER_LOG = LoggerFactory.getLogger("user");

   static {
      OPTIONS.addOption(new Option(VERSION_OPTION_KEY,
         "print the version information and exit"));
      OPTIONS.addOption(new Option(HELP_OPTION_KEY, "print this message"));
      OPTIONS.addOption(new Option(TEST_OPTION_KEY, "run embedded TestCase suite"));

      OptionBuilder.withArgName("file1[,file2...]");
      OptionBuilder.hasArg();
      OptionBuilder.withDescription("perform diff using given file(s) for plan");
      OPTIONS.addOption(OptionBuilder.create(PLAN_FILE_OPTION_KEY));
      OPTIONS.addOption(new Option(
         ERROR_ON_DIFF_OPTION_KEY,
         "exit with error status code (-1) if diffs are detected. otherwise will always exit with 0 unless an operating Exception was encountered"));
   }

   public static void main(String[] args_) {
      LOG.debug("args_->{}", Arrays.toString(args_));
      try {
         CommandLineParser parser = new PosixParser();
         CommandLine line = parser.parse(OPTIONS, args_);
         if (line.hasOption(VERSION_OPTION_KEY))
            printVersion();
         else if (line.hasOption(HELP_OPTION_KEY))
            printHelp();
         else if (line.hasOption(TEST_OPTION_KEY))
            runTestCases();
         else if (line.hasOption(PLAN_FILE_OPTION_KEY))
            runPlan(line.getOptionValue(PLAN_FILE_OPTION_KEY),
               line.hasOption(ERROR_ON_DIFF_OPTION_KEY));
         else
            printInvalidArguments(args_);
      }
      catch (ParseException e_) {
         System.err.println(e_.getMessage());
      }
      catch (Throwable e_) {
         Throwable rootCause = ExceptionUtils.getRootCause(e_);
         if ((rootCause instanceof DKUserException)
            || (rootCause instanceof FileNotFoundException)) {
            LOG.info(null, e_);
            USER_LOG.info("error->" + rootCause.getMessage());
         }
         else
            LOG.error(null, e_);
      }
   }

   private static void printVersion() {
      USER_LOG.info("version->" + DKDistProperties.getPublicVersionString());
      System.exit(0);
   }

   private static void printInvalidArguments(String[] args_) {
      USER_LOG.info(String.format("Invalid command line arguments: %s",
         Arrays.toString(args_)));
      printHelp();
   }

   private static void printHelp() {
      // automatically generate the help statement
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("java -jar diffkit-app.jar", OPTIONS);
   }

   @SuppressWarnings("unchecked")
   private static void runTestCases() {
      USER_LOG.info("running TestCases");
      try {
         Class<?> testCaseRunnerClass = Class.forName("org.diffkit.diff.testcase.TestCaseRunner");
         LOG.info("testCaseRunnerClass->{}", testCaseRunnerClass);
         Constructor<Runnable> constructor = (Constructor<Runnable>) DKClassUtil.findLongestConstructor(testCaseRunnerClass);
         LOG.debug("constructor->{}", constructor);
         Runnable testCaseRunner = constructor.newInstance((Object) null);
         testCaseRunner.run();
      }
      catch (Exception e_) {
         LOG.error(null, e_);
      }
   }

   private static void runPlan(String planFilesString_, boolean errorOnDiff_)
      throws Exception {
      LOG.info("planFilesString_->{}", planFilesString_);
      String[] planFiles = planFilesString_.split("\\,");
      USER_LOG.info("planfile(s)->{}", planFiles);
      AbstractXmlApplicationContext appContext = getContext(planFiles);
      LOG.info("appContext->{}", appContext);
      DKPlan plan = (DKPlan) appContext.getBean("plan");
      LOG.info("plan->{}", plan);
      if (plan == null)
         throw new RuntimeException(String.format("no 'plan' bean in  planFiles_->",
            planFilesString_));
      DKDiffEngine engine = new DKDiffEngine();
      LOG.info("engine->{}", engine);
      DKSource lhsSource = plan.getLhsSource();
      DKSource rhsSource = plan.getRhsSource();
      DKSink sink = plan.getSink();
      DKTableComparison tableComparison = plan.getTableComparison();
      USER_LOG.info("lhsSource->{}", lhsSource);
      USER_LOG.info("rhsSource->{}", rhsSource);
      USER_LOG.info("sink->{}", sink);
      USER_LOG.info("tableComparison->{}", tableComparison);
      DKContext diffContext = engine.diff(lhsSource, rhsSource, sink, tableComparison);
      USER_LOG.info("---");
      USER_LOG.info("diff'd {} rows, found:", diffContext._rowStep);
      if (plan.getSink().getDiffCount() == 0) {
         USER_LOG.info("(no diffs)");
         System.exit(0);
      }
      USER_LOG.info("!{} row diffs", plan.getSink().getRowDiffCount());
      USER_LOG.info("@{} column diffs", plan.getSink().getColumnDiffCount());
      if (errorOnDiff_)
         System.exit(-1);
      System.exit(0);
   }

   /**
    * @param planFilePath_
    *           can be either a FS file path (relative or absolute) or it can be
    *           a resource style path that will be resolved via the classpath
    */
   private static AbstractXmlApplicationContext getContext(String[] planFiles_) {
      LOG.debug("planFiles_->{}", planFiles_);
      AbstractXmlApplicationContext context = null;
      if (canReadPlanFilePaths(planFiles_))
         context = new FileSystemXmlApplicationContext(planFiles_, false);
      else
         context = new ClassPathXmlApplicationContext(planFiles_);
      context.setClassLoader(DKApplication.class.getClassLoader());
      context.refresh();
      return context;
   }

   private static boolean canReadPlanFilePaths(String[] planFiles_) {
      if (planFiles_ == null)
         return false;
      for (String filePath : planFiles_) {
         if (!new File(filePath).canRead())
            return false;
      }
      return true;
   }
}
