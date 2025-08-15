// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.cucumber.java.steps;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.search.SearchScope;
import com.intellij.util.CommonProcessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.CucumberUtil;
import org.jetbrains.plugins.cucumber.ParameterTypeManager;
import org.jetbrains.plugins.cucumber.java.CucumberJavaUtil;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.steps.AbstractStepDefinition;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public abstract class AbstractJavaStepDefinition extends AbstractStepDefinition {

  private static final Pattern ESCAPE_PATTERN = Pattern.compile("(#\\{.+?})");

  private static final String CUCUMBER_START_PREFIX = "\\A";
  private static final String CUCUMBER_END_SUFFIX = "\\z";
  private static final int TIME_TO_CHECK_STEP_BY_REGEXP_MILLIS = 300;

  private final SmartPsiElementPointer<PsiElement> myElementPointer;

  /**
   * A cache for compiled regex patterns.
   * Key: A record containing the original regex string and its case-sensitivity.
   * Value: The compiled {@link Pattern} object.
   * This avoids repeatedly compiling the same regex strings across different step definition instances.
   */
  public record PatternCacheKey(@NotNull String regex, boolean isCaseSensitive) {}

  public static final Cache<@NotNull PatternCacheKey, Pattern> PATTERN_CACHE =
      Caffeine.newBuilder()
          .expireAfterAccess(java.time.Duration.ofMinutes(30))
          .build();

  public static final Cache<@NotNull String, String> CUCUMBER_EXPRESSION_CACHE = Caffeine.newBuilder()
      .expireAfterAccess(java.time.Duration.ofMinutes(30))
      .build();

  private final Module module;

  public AbstractJavaStepDefinition(@NotNull PsiElement element, Module module) {
    super(element);
    this.module = module;
    myElementPointer = SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(element);
  }

  /**
   * @return true if a concrete step named {@code stepName} is defined by this step definition.
   */
  public boolean matches(@NotNull String stepName) {
    final Pattern pattern = getPattern();
    if (pattern == null) {
      return false;
    }

    CharSequence stepChars = StringUtil.newBombedCharSequence(stepName, TIME_TO_CHECK_STEP_BY_REGEXP_MILLIS);
    try {
      return pattern.matcher(stepChars).find();
    }
    catch (CancellationException ignore) {
      return false;
    }
  }

  public @Nullable PsiElement getElement() {
    return myElementPointer.getElement();
  }

  /**
   * @return regexp pattern for a step, or null if the regex is malformed.
   * This method uses a shared {@link Caffeine} cache to improve performance by reusing compiled patterns.
   */
  public @Nullable Pattern getPattern() {
    final String cucumberRegex = getCucumberRegex();
    if (cucumberRegex == null) {
      return null;
    }

    try {
      PatternCacheKey key = new PatternCacheKey(cucumberRegex, isCaseSensitive());
      return PATTERN_CACHE.get(key, k -> {
        final StringBuilder patternText = new StringBuilder(ESCAPE_PATTERN.matcher(k.regex()).replaceAll("(.*)"));

        String temp = patternText.toString();
        if (temp.startsWith(CUCUMBER_START_PREFIX)) {
          patternText.replace(0, CUCUMBER_START_PREFIX.length(), "^");
        }
        if (temp.endsWith(CUCUMBER_END_SUFFIX)) {
          patternText.replace(patternText.length() - CUCUMBER_END_SUFFIX.length(), patternText.length(), "$");
        }

        return Pattern.compile(patternText.toString(), k.isCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE);
      });
    } catch (PatternSyntaxException e) {
      // Return null if the regex is invalid, maintaining the original contract.
      return null;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    return myElementPointer.equals(((AbstractJavaStepDefinition) o).myElementPointer);
  }

  @Override
  public int hashCode() {
    return myElementPointer.hashCode();
  }

  /**
   * Sets the new value for this step definition (either a regex or a Cucumber Expression).
   *
   * What the value exactly is depends on the particular Cucumber implementation in some programming language.
   * For example, it could be a string inside the annotation {@code @When} or a method name.
   */
  public void setValue(@NotNull String newValue) { }

  /**
   * @return True if this step definition supports some certain {@code step} (e.g., some step definitions do not support some keywords).
   */
  public boolean supportsStep(@NotNull PsiElement step) {
    return true;
  }

  /**
   * If {@code newName} is not null, it returns true if this step definition can be renamed to this specific new name.
   *
   * If {@code newName} is null, it returns true if this step definition can be renamed at all.
   */
  public boolean supportsRename(@Nullable String newName) {
    return true;
  }

  /**
   * Finds all steps that refer to this definition in some {@link SearchScope} (kind of like "find usages").
   */
  public @NotNull Collection<GherkinStep> findSteps(@NotNull SearchScope searchScope) {
    final String regex = getCucumberRegex();
    final PsiElement element = getElement();
    if (regex == null || element == null) {
      return Collections.emptyList();
    }

    final CommonProcessors.CollectProcessor<PsiReference>
        consumer = new CommonProcessors.CollectProcessor<>();
    CucumberUtil.findGherkinReferencesToElement(element, regex, consumer, searchScope);

    // We use a hash set to get rid of duplicates
    final Collection<GherkinStep> results = new HashSet<>(consumer.getResults().size());
    for (final PsiReference reference : consumer.getResults()) {
      if (reference.getElement() instanceof GherkinStep gherkinStep) {
        results.add(gherkinStep);
      }
    }
    return results;
  }

  /**
   * Returns either a regex or a Cucumber Expression associated with this step.
   */
  public @Nullable String getExpression() {
    return getCucumberRegexFromElement(getElement());
  }

  @Override
  public @Nullable String getCucumberRegex() {
    String definitionText = getExpression();

    if (definitionText == null) return null;
    String cachedExpression = CUCUMBER_EXPRESSION_CACHE.getIfPresent(definitionText);
    if (cachedExpression != null) {
      return cachedExpression;
    }

    PsiElement element = getElement();
    if (element == null) return null;

    if (CucumberUtil.isCucumberExpression(definitionText)) {
      ParameterTypeManager parameterTypes = CucumberJavaUtil.getAllParameterTypes(module);

      String expression = CucumberUtil.buildRegexpFromCucumberExpression(definitionText, parameterTypes);
      CUCUMBER_EXPRESSION_CACHE.put(definitionText, expression);

      return expression;
    }

    return definitionText;
  }

  @Override
  public List<String> getVariableNames() {
    PsiElement element = getElement();
    if (element instanceof PsiMethod method) {
      PsiParameter[] parameters = method.getParameterList().getParameters();
      ArrayList<String> result = new ArrayList<>();
      for (PsiParameter parameter : parameters) {
        result.add(parameter.getName());
      }
      return result;
    }
    return Collections.emptyList();
  }

}
