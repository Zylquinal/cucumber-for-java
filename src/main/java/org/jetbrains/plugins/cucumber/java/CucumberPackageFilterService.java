package org.jetbrains.plugins.cucumber.java;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.java.settings.CucumberPackageFilterSettingsState;
import org.jetbrains.plugins.cucumber.java.steps.AbstractJavaStepDefinition;
import org.jetbrains.plugins.cucumber.java.steps.factory.JavaStepDefinitionFactory;
import org.jetbrains.plugins.cucumber.steps.AbstractStepDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.jetbrains.plugins.cucumber.java.CucumberJavaExtension.CUCUMBER_JAVA_STEP_DEFINITION_ANNOTATION_CLASSES;

@Service(Service.Level.PROJECT)
@State(
    name = "org.jetbrains.plugins.cucumber.java.CucumberPackageFilterSettingsState",
    storages = @Storage("cucumber-package-filter.xml")
)
public final class CucumberPackageFilterService implements PersistentStateComponent<CucumberPackageFilterSettingsState> {

  public final Cache<@NotNull String, AbstractStepDefinition> STEP_DEFINITION_CACHE = Caffeine.newBuilder()
      .expireAfterAccess(Duration.ofMinutes(60))
      .build();

  private final Project project;
  private CucumberPackageFilterSettingsState state = new CucumberPackageFilterSettingsState();
  private CachedValue<List<AbstractStepDefinition>> cachedSteps;

  public CucumberPackageFilterService(Project project) {
    this.project = project;
  }

  @Override
  public @NotNull CucumberPackageFilterSettingsState getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull CucumberPackageFilterSettingsState state) {
    this.state = state;
  }

  public static CucumberPackageFilterService getInstance(@NotNull Project project) {
    return project.getService(CucumberPackageFilterService.class);
  }

  public List<AbstractStepDefinition> getSteps(@NotNull Module module) {
    if (cachedSteps == null) {
      cachedSteps = CachedValuesManager.getManager(project).createCachedValue(() -> {
        List<AbstractStepDefinition> computedResult = computeSteps(module);
        return CachedValueProvider.Result.create(computedResult,
            PsiModificationTracker.MODIFICATION_COUNT,
            this
        );
      });
    }

    return cachedSteps.getValue();
  }

  private List<AbstractStepDefinition> computeSteps(@NotNull Module module) {
    final Project project = module.getProject();
    final GlobalSearchScope broadScope = module.getModuleWithDependenciesAndLibrariesScope(true);
    final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
    final JavaStepDefinitionFactory stepDefinitionFactory = JavaStepDefinitionFactory.getInstance(module);
    final List<AbstractStepDefinition> result = new ArrayList<>();

    final List<String> packagePrefixes = this.getState().packageNames
        .stream()
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();

    PsiClass stepDefAnnotationClass = null;
    for (String className : CUCUMBER_JAVA_STEP_DEFINITION_ANNOTATION_CLASSES) {
      stepDefAnnotationClass = psiFacade.findClass(className, broadScope);
      if (stepDefAnnotationClass != null) {
        break;
      }
    }

    if (stepDefAnnotationClass == null) {
      return Collections.emptyList();
    }

    final Query<PsiClass> stepDefAnnotations = AnnotatedElementsSearch.searchPsiClasses(stepDefAnnotationClass, broadScope);

    for (PsiClass annotationClass : stepDefAnnotations.asIterable()) {
      String annotationClassName = annotationClass.getQualifiedName();
      if (annotationClass.isAnnotationType() && annotationClassName != null) {

        AnnotatedElementsSearch.searchPsiMethods(annotationClass, broadScope).forEach(stepDefMethod -> {
          if (!(stepDefMethod.getContainingFile() instanceof PsiJavaFile containingFile)) {
            return true;
          }

          String fullClassLocation = containingFile.getVirtualFile().getPath();
          boolean isMatch;
          if (packagePrefixes.isEmpty()) {
            isMatch = true;
          } else {
            isMatch = false;
            String packageName = containingFile.getPackageName();
            for (String prefix : packagePrefixes) {
              if (packageName.equals(prefix) || packageName.startsWith(prefix + ".")) {
                isMatch = true;
                break;
              }
            }
          }

          if (isMatch) {
            List<String> annotationValues = CucumberJavaUtil.getStepAnnotationValues(stepDefMethod, annotationClassName);
            for (String annotationValue : annotationValues) {
              var compositeKey = fullClassLocation + ":" + annotationValue;
              var cachedStepDefinition = STEP_DEFINITION_CACHE.getIfPresent(compositeKey);
              if (cachedStepDefinition != null) {
                ((AbstractJavaStepDefinition) cachedStepDefinition).updateElementIfNull(stepDefMethod);
                result.add(cachedStepDefinition);
                continue;
              }

              final AbstractStepDefinition newStepDefinition = stepDefinitionFactory.buildStepDefinition(
                  stepDefMethod,
                  module,
                  annotationValue
              );
              result.add(newStepDefinition);
              STEP_DEFINITION_CACHE.put(compositeKey, newStepDefinition);
            }
          }

          return true;
        });
      }
    }

    return result;
  }


}
