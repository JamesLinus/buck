/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

/** Generate the CFG for a source file */
public class CxxInferCapture extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements SupportsDependencyFileRuleKey {

  @AddToRuleKey private final InferBuckConfig inferConfig;
  @AddToRuleKey private final CxxToolFlags preprocessorFlags;
  @AddToRuleKey private final CxxToolFlags compilerFlags;
  @AddToRuleKey private final SourcePath input;
  private final CxxSource.Type inputType;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final PreprocessorDelegate preprocessorDelegate;

  private final Path resultsDir;

  CxxInferCapture(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      CxxToolFlags preprocessorFlags,
      CxxToolFlags compilerFlags,
      SourcePath input,
      AbstractCxxSource.Type inputType,
      Path output,
      PreprocessorDelegate preprocessorDelegate,
      InferBuckConfig inferConfig) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.preprocessorFlags = preprocessorFlags;
    this.compilerFlags = compilerFlags;
    this.input = input;
    this.inputType = inputType;
    this.output = output;
    this.preprocessorDelegate = preprocessorDelegate;
    this.inferConfig = inferConfig;
    this.resultsDir =
        BuildTargets.getGenPath(getProjectFilesystem(), this.getBuildTarget(), "infer-out-%s");
  }

  private CxxToolFlags getSearchPathFlags() {
    return preprocessorDelegate.getFlagsWithSearchPaths(/* no pch */ Optional.empty());
  }

  private ImmutableList<String> getFrontendCommand() {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    return commandBuilder
        .add(this.inferConfig.getInferTopLevel().toString())
        .add("-a", "capture")
        .add("--project_root", getProjectFilesystem().getRootPath().toString())
        .add("--out", resultsDir.toString())
        .add("--")
        .add("clang")
        .add("@" + getArgfile())
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList<String> frontendCommand = getFrontendCommand();

    buildableContext.recordArtifact(
        context.getSourcePathResolver().getRelativePath(getSourcePathToOutput()));

    Path inputRelativePath = context.getSourcePathResolver().getRelativePath(input);
    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), resultsDir)))
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())))
        .add(new WriteArgFileStep(context.getSourcePathResolver(), inputRelativePath))
        .add(
            new DefaultShellStep(
                getProjectFilesystem().getRootPath(), frontendCommand, ImmutableMap.of()))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), resultsDir);
  }

  public Path getAbsolutePathToOutput() {
    return getProjectFilesystem().resolve(resultsDir);
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return true;
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate() {
    return preprocessorDelegate.getCoveredByDepFilePredicate();
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate() {
    return (SourcePath path) -> false;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) throws IOException {

    ImmutableList<Path> depFileLines;
    try {
      depFileLines =
          Depfiles.parseAndOutputBuckCompatibleDepfile(
              context.getEventBus(),
              getProjectFilesystem(),
              preprocessorDelegate.getHeaderPathNormalizer(),
              preprocessorDelegate.getHeaderVerification(),
              getDepFilePath(),
              context.getSourcePathResolver().getRelativePath(input),
              output);
    } catch (Depfiles.HeaderVerificationException e) {
      throw new HumanReadableException(e);
    }

    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // include all inputs coming from the preprocessor tool.
    inputs.addAll(preprocessorDelegate.getInputsAfterBuildingLocally(depFileLines));

    // Add the input.
    inputs.add(input);

    return inputs.build();
  }

  private Path getArgfile() {
    return output
        .getFileSystem()
        .getPath(output.getParent().resolve("infer-capture.argsfile").toString());
  }

  private Path getDepFilePath() {
    return output.getFileSystem().getPath(output.toString() + ".dep");
  }

  private class WriteArgFileStep implements Step {

    private final SourcePathResolver pathResolver;
    private final Path inputRelativePath;

    public WriteArgFileStep(SourcePathResolver pathResolver, Path inputRelativePath) {
      this.pathResolver = pathResolver;
      this.inputRelativePath = inputRelativePath;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      getProjectFilesystem()
          .writeLinesToPath(
              Iterables.transform(getCompilerArgs(), Escaper.ARGFILE_ESCAPER), getArgfile());
      return StepExecutionResult.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "write-argfile";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return "Write argfile for clang";
    }

    private ImmutableList<String> getCompilerArgs() {
      ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
      return commandBuilder
          .add("-MD", "-MF", getDepFilePath().toString())
          .addAll(
              Arg.stringify(
                  CxxToolFlags.concat(preprocessorFlags, getSearchPathFlags(), compilerFlags)
                      .getAllFlags(),
                  pathResolver))
          .add("-x", inputType.getLanguage())
          .add("-o", output.toString()) // TODO(martinoluca): Use -fsyntax-only for better perf
          .add("-c")
          .add(inputRelativePath.toString())
          .build();
    }
  }
}
