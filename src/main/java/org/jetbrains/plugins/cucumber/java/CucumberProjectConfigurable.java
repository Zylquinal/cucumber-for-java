package org.jetbrains.plugins.cucumber.java;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.ListTableModel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.java.settings.CucumberPackageFilterSettingsState.StepDefinitionMatcher;
import org.jetbrains.plugins.cucumber.java.steps.AbstractJavaStepDefinition;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class CucumberProjectConfigurable implements Configurable {

  private final Project myProject;
  private final CucumberPackageFilterService mySettingsService;

  private ListTableModel<StringHolder> myPackagesModel;
  private JBTable myPackagesTable;
  private ListTableModel<StringHolder> myPluginsModel;
  private JBTable myPluginsTable;
  private ListTableModel<StringHolder> myVmOptionsModel;
  private JBTable myVmOptionsTable;
  private ListTableModel<EnvironmentVariable> myEnvVarsModel;
  private JBTable myEnvVarsTable;
  private ComboBox<StepDefinitionMatcher> myStepDefinitionMatcherComboBox;


  private EditorTextField myRunnerClassEditor;
  private JBLabel myWarningLabel;

  private JBLabel myPatternCacheStatsLabel;
  private JBLabel myExpressionCacheStatsLabel;
  private JBLabel myStepDefCacheStatsLabel;
  private JSpinner myRefreshTimeSpinner;
  private Timer myRefreshTimer;

  public CucumberProjectConfigurable(@NotNull Project project) {
    myProject = project;
    mySettingsService = CucumberPackageFilterService.getInstance(project);
  }

  @Nls(capitalization = Nls.Capitalization.Title)
  @Override
  public String getDisplayName() {
    return "Cucumber Settings";
  }

  @Nullable
  @Override
  public JComponent createComponent() {
    myRunnerClassEditor = new EditorTextField();

    myWarningLabel = new JBLabel(AllIcons.General.Warning);
    myWarningLabel.setVisible(false);

    myStepDefinitionMatcherComboBox = new ComboBox<>(StepDefinitionMatcher.values());

    myRunnerClassEditor.addFocusListener(new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        validateRunnerClass();
      }
    });

    JPanel runnerPanel = new JPanel(new BorderLayout());
    runnerPanel.add(myRunnerClassEditor, BorderLayout.CENTER);
    runnerPanel.add(myWarningLabel, BorderLayout.EAST);

    // Setup Packages Table
    setupPackagesTable();
    ToolbarDecorator packagesDecorator = ToolbarDecorator.createDecorator(myPackagesTable);
    packagesDecorator.setAddAction(button -> {
      myPackagesModel.addRow(new StringHolder(""));
      myPackagesTable.editCellAt(myPackagesModel.getRowCount() - 1, 0);
    });

    // Setup Plugins Table
    setupPluginsTable();
    ToolbarDecorator pluginsDecorator = ToolbarDecorator.createDecorator(myPluginsTable);
    pluginsDecorator.setAddAction(button -> {
      myPluginsModel.addRow(new StringHolder(""));
      myPluginsTable.editCellAt(myPluginsModel.getRowCount() - 1, 0);
    });

    // Setup VM Options Table
    setupVmOptionsTable();
    ToolbarDecorator vmOptionsDecorator = ToolbarDecorator.createDecorator(myVmOptionsTable);
    vmOptionsDecorator.setAddAction(button -> {
      myVmOptionsModel.addRow(new StringHolder(""));
      myVmOptionsTable.editCellAt(myVmOptionsModel.getRowCount() - 1, 0);
    });

    // Setup Environment Variables Table
    setupEnvVarsTable();
    ToolbarDecorator envVarsDecorator = ToolbarDecorator.createDecorator(myEnvVarsTable);
    envVarsDecorator.setAddAction(button -> {
      myEnvVarsModel.addRow(new EnvironmentVariable("", ""));
      myEnvVarsTable.editCellAt(myEnvVarsModel.getRowCount() - 1, 0);
    });

    myPatternCacheStatsLabel = new JBLabel("Pattern Cache: (loading...)");
    myExpressionCacheStatsLabel = new JBLabel("Expression Cache: (loading...)");
    myStepDefCacheStatsLabel = new JBLabel("Step Definition Cache: (loading...)");

    SpinnerNumberModel spinnerModel = new SpinnerNumberModel(5, 1, 60, 1);
    myRefreshTimeSpinner = new JSpinner(spinnerModel);

    JPanel refreshPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    refreshPanel.add(new JBLabel("Refresh every (seconds): "));
    refreshPanel.add(myRefreshTimeSpinner);

    setupRefreshTimer();
    myRefreshTimeSpinner.addChangeListener(e -> {
      if (myRefreshTimer != null) {
        int seconds = (int) myRefreshTimeSpinner.getValue();
        myRefreshTimer.setDelay(seconds * 1000);
        myRefreshTimer.restart();
      }
    });

    JButton evictAllButton = new JButton("Evict All Caches");
    evictAllButton.addActionListener(e -> {
      AbstractJavaStepDefinition.PATTERN_CACHE.invalidateAll();
      AbstractJavaStepDefinition.CUCUMBER_EXPRESSION_CACHE.invalidateAll();
      CucumberPackageFilterService.getInstance(myProject).STEP_DEFINITION_CACHE.invalidateAll();
      updateCacheStats();
    });

    JPanel evictButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
    evictButtonsPanel.add(evictAllButton);

    return FormBuilder.createFormBuilder()
        .addLabeledComponent("Default Cucumber Runner Class:", runnerPanel)
        .addTooltip("Enter the fully qualified name of the default test runner class.")
        .addLabeledComponent("Step Definition Matching Algorithm:", myStepDefinitionMatcherComboBox)
        .addTooltip("Choose how to match step definitions to Gherkin steps.")
        .addVerticalGap(10)
        .addLabeledComponent("VM Options:", vmOptionsDecorator.createPanel(), true)
        .addTooltip("Additional Java VM parameters for running Cucumber.")
        .addVerticalGap(10)
        .addLabeledComponent("Cucumber Plugins:", pluginsDecorator.createPanel(), true)
        .addTooltip("e.g., pretty, json:target/cucumber.json")
        .addVerticalGap(10)
        .addLabeledComponent("Environment Variables:", envVarsDecorator.createPanel(), true)
        .addVerticalGap(10)
        .addLabeledComponent("Scan for step definitions only in these packages (and sub-packages):", packagesDecorator.createPanel(), true)
        .addTooltip("If this list is empty, the entire project and its dependencies will be scanned.")
        .addSeparator(20)
        .addComponent(new JBLabel("Cache Statistics"))
        .addSeparator(10)
        .addComponent(myPatternCacheStatsLabel)
        .addComponent(myExpressionCacheStatsLabel)
        .addComponent(myStepDefCacheStatsLabel)
        .addVerticalGap(5)
        .addComponent(refreshPanel)
        .addVerticalGap(5)
        .addComponent(evictButtonsPanel)
        .addSeparator(5)
        .getPanel();
  }

  private void setupPackagesTable() {
    ColumnInfo<StringHolder, String> columnInfo = new ColumnInfo<StringHolder, String>("Package") {
      @Nullable
      @Override
      public String valueOf(StringHolder holder) {
        return holder.getValue();
      }

      @Override
      public void setValue(StringHolder holder, String value) {
        holder.setValue(value);
      }

      @Override
      public boolean isCellEditable(StringHolder holder) {
        return true;
      }
    };

    myPackagesModel = new ListTableModel<>(new ColumnInfo[]{columnInfo}, new ArrayList<>());
    myPackagesTable = new JBTable(myPackagesModel);
    myPackagesTable.setShowGrid(false);
    myPackagesTable.setTableHeader(null);
  }

  private void setupPluginsTable() {
    ColumnInfo<StringHolder, String> columnInfo = new ColumnInfo<StringHolder, String>("Plugin") {
      @Nullable
      @Override
      public String valueOf(StringHolder holder) {
        return holder.getValue();
      }

      @Override
      public void setValue(StringHolder holder, String value) {
        holder.setValue(value);
      }

      @Override
      public boolean isCellEditable(StringHolder holder) {
        return true;
      }
    };

    myPluginsModel = new ListTableModel<>(new ColumnInfo[]{columnInfo}, new ArrayList<>());
    myPluginsTable = new JBTable(myPluginsModel);
    myPluginsTable.setShowGrid(false);
    myPluginsTable.setTableHeader(null);
  }

  private void setupVmOptionsTable() {
    ColumnInfo<StringHolder, String> columnInfo = new ColumnInfo<StringHolder, String>("VM Option") {
      @Nullable
      @Override
      public String valueOf(StringHolder holder) {
        return holder.getValue();
      }

      @Override
      public void setValue(StringHolder holder, String value) {
        holder.setValue(value);
      }

      @Override
      public boolean isCellEditable(StringHolder holder) {
        return true;
      }
    };

    myVmOptionsModel = new ListTableModel<>(new ColumnInfo[]{columnInfo}, new ArrayList<>());
    myVmOptionsTable = new JBTable(myVmOptionsModel);
    myVmOptionsTable.setShowGrid(false);
    myVmOptionsTable.setTableHeader(null);
  }

  private void setupEnvVarsTable() {
    ColumnInfo<EnvironmentVariable, String> nameColumn = new ColumnInfo<EnvironmentVariable, String>("Name") {
      @Nullable
      @Override
      public String valueOf(EnvironmentVariable var) {
        return var.getName();
      }

      @Override
      public void setValue(EnvironmentVariable var, String value) {
        var.setName(value);
      }

      @Override
      public boolean isCellEditable(EnvironmentVariable var) {
        return true;
      }
    };

    ColumnInfo<EnvironmentVariable, String> valueColumn = new ColumnInfo<EnvironmentVariable, String>("Value") {
      @Nullable
      @Override
      public String valueOf(EnvironmentVariable var) {
        return var.getValue();
      }

      @Override
      public void setValue(EnvironmentVariable var, String value) {
        var.setValue(value);
      }

      @Override
      public boolean isCellEditable(EnvironmentVariable var) {
        return true;
      }
    };

    myEnvVarsModel = new ListTableModel<>(new ColumnInfo[]{nameColumn, valueColumn}, new ArrayList<>());
    myEnvVarsTable = new JBTable(myEnvVarsModel);
  }

  private void setupRefreshTimer() {
    int initialSeconds = (int) myRefreshTimeSpinner.getValue();
    myRefreshTimer = new Timer(initialSeconds * 1000, e -> updateCacheStats());
    myRefreshTimer.setInitialDelay(0);
    myRefreshTimer.start();
  }

  private void updateCacheStats() {
    String patternStats = formatCacheStats(AbstractJavaStepDefinition.PATTERN_CACHE);
    String expressionStats = formatCacheStats(AbstractJavaStepDefinition.CUCUMBER_EXPRESSION_CACHE);
    String stepDefStats = formatCacheStats(CucumberPackageFilterService.getInstance(myProject).STEP_DEFINITION_CACHE);

    myPatternCacheStatsLabel.setText("Pattern Cache: " + patternStats);
    myExpressionCacheStatsLabel.setText("Expression Cache: " + expressionStats);
    myStepDefCacheStatsLabel.setText("Step Definition Cache (Project): " + stepDefStats);
  }

  private String formatCacheStats(@NotNull Cache<?, ?> cache) {
    CacheStats stats = cache.stats();
    long size = cache.estimatedSize();
    return String.format("Size: %d, Hits: %d, Misses: %d, Evictions: %d, Hit Rate: %.1f%%",
        size, stats.hitCount(), stats.missCount(), stats.evictionCount(), stats.hitRate() * 100);
  }


  private void validateRunnerClass() {
    String className = myRunnerClassEditor.getText();
    if (StringUtil.isEmpty(className)) {
      myWarningLabel.setVisible(false);
      return;
    }

    ReadAction.nonBlocking(() -> {
          GlobalSearchScope scope = GlobalSearchScope.allScope(myProject);
          PsiClass psiClass = JavaPsiFacade.getInstance(myProject).findClass(className, scope);
          return psiClass != null && psiClass.hasAnnotation("io.cucumber.junit.CucumberOptions");
        })
        .finishOnUiThread(ModalityState.stateForComponent(myRunnerClassEditor), (Boolean isValid) -> {
          if (!isValid) {
            myWarningLabel.setVisible(true);
            myWarningLabel.setToolTipText("Class not found or is not a valid Cucumber runner (missing @CucumberOptions annotation)");
          } else {
            myWarningLabel.setVisible(false);
            myWarningLabel.setToolTipText(null);
          }
        })
        .submit(AppExecutorUtil.getAppExecutorService());
  }

  private List<String> getCleanStringList(ListTableModel<StringHolder> model) {
    return model.getItems().stream()
        .map(StringHolder::getValue)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }

  @Override
  public boolean isModified() {
    boolean matcherModified = !Objects.equals(myStepDefinitionMatcherComboBox.getSelectedItem(), mySettingsService.getState().stepDefinitionMatcher);
    boolean packagesModified = !getCleanStringList(myPackagesModel).equals(mySettingsService.getState().packageNames);
    boolean pluginsModified = !getCleanStringList(myPluginsModel).equals(mySettingsService.getState().plugins);
    boolean vmOptionsModified = !getCleanStringList(myVmOptionsModel).equals(mySettingsService.getState().vmOptions);

    String uiRunner = myRunnerClassEditor.getText();
    String settingsRunner = StringUtil.notNullize(mySettingsService.getState().cucumberRunner);
    boolean runnerModified = !Objects.equals(uiRunner, settingsRunner);

    Map<String, String> uiEnvVars = myEnvVarsModel.getItems().stream()
        .filter(v -> v.getName() != null && !v.getName().trim().isEmpty())
        .collect(Collectors.toMap(v -> v.getName().trim(), EnvironmentVariable::getValue, (v1, v2) -> v1));
    Map<String, String> settingsEnvVars = mySettingsService.getState().envVars;
    boolean envVarsModified = !Objects.equals(uiEnvVars, settingsEnvVars);

    return packagesModified || pluginsModified || vmOptionsModified || runnerModified || envVarsModified || matcherModified;
  }

  @Override
  public void apply() {
    mySettingsService.getState().stepDefinitionMatcher = (StepDefinitionMatcher) myStepDefinitionMatcherComboBox.getSelectedItem();
    mySettingsService.getState().packageNames = getCleanStringList(myPackagesModel);
    mySettingsService.getState().plugins = getCleanStringList(myPluginsModel);
    mySettingsService.getState().vmOptions = getCleanStringList(myVmOptionsModel);
    mySettingsService.getState().cucumberRunner = myRunnerClassEditor.getText();

    mySettingsService.getState().envVars = myEnvVarsModel.getItems().stream()
        .filter(v -> v.getName() != null && !v.getName().trim().isEmpty())
        .collect(Collectors.toMap(v -> v.getName().trim(), EnvironmentVariable::getValue, (v1, v2) -> v2, HashMap::new));
  }

  @Override
  public void reset() {
    myStepDefinitionMatcherComboBox.setSelectedItem(mySettingsService.getState().stepDefinitionMatcher);
    List<String> packageNames = mySettingsService.getState().packageNames;
    myPackagesModel.setItems(packageNames != null ? packageNames.stream().map(StringHolder::new).collect(Collectors.toList()) : new ArrayList<>());

    List<String> plugins = mySettingsService.getState().plugins;
    myPluginsModel.setItems(plugins != null ? plugins.stream().map(StringHolder::new).collect(Collectors.toList()) : new ArrayList<>());

    List<String> vmOptions = mySettingsService.getState().vmOptions;
    myVmOptionsModel.setItems(vmOptions != null ? vmOptions.stream().map(StringHolder::new).collect(Collectors.toList()) : new ArrayList<>());

    myRunnerClassEditor.setText(mySettingsService.getState().cucumberRunner);
    validateRunnerClass();

    Map<String, String> envVars = mySettingsService.getState().envVars;
    List<EnvironmentVariable> envVarsList = new ArrayList<>();
    if (envVars != null) {
      envVars.forEach((key, value) -> envVarsList.add(new EnvironmentVariable(key, value)));
    }
    myEnvVarsModel.setItems(envVarsList);
  }

  @Override
  public void disposeUIResources() {
    if (myRefreshTimer != null) {
      myRefreshTimer.stop();
      myRefreshTimer = null;
    }
  }

  public static class StringHolder {
    private String value;

    public StringHolder(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }

  public static class EnvironmentVariable {
    private String name;
    private String value;

    public EnvironmentVariable(String name, String value) {
      this.name = name;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }
}
