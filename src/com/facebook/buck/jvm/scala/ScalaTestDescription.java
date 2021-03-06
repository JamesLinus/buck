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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.ForkMode;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasContacts;
import com.facebook.buck.rules.HasTestTimeout;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.util.Optional;
import java.util.logging.Level;
import org.immutables.value.Value;

public class ScalaTestDescription
    implements Description<ScalaTestDescriptionArg>,
        ImplicitDepsInferringDescription<ScalaTestDescription.AbstractScalaTestDescriptionArg> {

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(ImmutableMap.of("location", new LocationMacroExpander()));

  private final ScalaBuckConfig config;
  private final JavaOptions javaOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;

  public ScalaTestDescription(
      ScalaBuckConfig config,
      JavaOptions javaOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform) {
    this.config = config;
    this.javaOptions = javaOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public Class<ScalaTestDescriptionArg> getConstructorArgType() {
    return ScalaTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams rawParams,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      ScalaTestDescriptionArg args)
      throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            buildTarget,
            projectFilesystem,
            rawParams,
            args.getUseCxxLibraries(),
            args.getCxxLibraryWhitelist(),
            resolver,
            ruleFinder,
            cxxPlatform);
    BuildRuleParams params = cxxLibraryEnhancement.updatedParams;
    BuildTarget javaLibraryBuildTarget =
        buildTarget.withAppendedFlavors(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);

    ScalaLibraryBuilder scalaLibraryBuilder =
        new ScalaLibraryBuilder(
                targetGraph,
                javaLibraryBuildTarget,
                projectFilesystem,
                params,
                resolver,
                cellRoots,
                config)
            .setArgs(args);

    if (HasJavaAbi.isAbiTarget(buildTarget)) {
      return scalaLibraryBuilder.buildAbi();
    }

    Function<String, Arg> toMacroArgFunction =
        MacroArg.toMacroArgFunction(MACRO_HANDLER, buildTarget, cellRoots, resolver);
    JavaLibrary testsLibrary = resolver.addToIndex(scalaLibraryBuilder.build());

    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return new JavaTest(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(testsLibrary)).withoutExtraDeps(),
        pathResolver,
        testsLibrary,
        /* additionalClasspathEntries */ ImmutableSet.of(),
        args.getLabels(),
        args.getContacts(),
        args.getTestType(),
        javaOptions.getJavaRuntimeLauncher(),
        args.getVmArgs(),
        cxxLibraryEnhancement.nativeLibsEnvironment,
        args.getTestRuleTimeoutMs().map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.getTestCaseTimeoutMs(),
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), toMacroArgFunction::apply)),
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.getStdOutLogLevel(),
        args.getStdErrLogLevel());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractScalaTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.add(config.getScalaLibraryTarget());
    Optionals.addIfPresent(config.getScalacTarget(), extraDepsBuilder);
    for (String envValue : constructorArg.getEnv().values()) {
      try {
        MACRO_HANDLER.extractParseTimeDeps(
            buildTarget, cellRoots, envValue, extraDepsBuilder, targetGraphOnlyDepsBuilder);
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractScalaTestDescriptionArg
      extends HasContacts, HasTestTimeout, ScalaLibraryDescription.CoreArg {
    ImmutableList<String> getVmArgs();

    @Value.Default
    default TestType getTestType() {
      return TestType.JUNIT;
    }

    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.Default
    default ForkMode getForkMode() {
      return ForkMode.NONE;
    }

    Optional<Level> getStdErrLogLevel();

    Optional<Level> getStdOutLogLevel();

    Optional<Boolean> getUseCxxLibraries();

    ImmutableSet<BuildTarget> getCxxLibraryWhitelist();

    Optional<Long> getTestCaseTimeoutMs();

    ImmutableMap<String, String> getEnv();
  }
}
