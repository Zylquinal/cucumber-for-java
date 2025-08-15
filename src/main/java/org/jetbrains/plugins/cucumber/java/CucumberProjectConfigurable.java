package org.jetbrains.plugins.cucumber.java;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
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
import org.jetbrains.plugins.cucumber.java.steps.AbstractJavaStepDefinition;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CucumberProjectConfigurable implements Configurable {

  private final Project myProject;
  private final CucumberPackageFilterService mySettingsService;

  private ListTableModel<String> myPackagesModel;
  private JBTable myPackagesTable;

  private EditorTextField mySpringPropertiesEditor;
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
    mySpringPropertiesEditor = new EditorTextField();

    myWarningLabel = new JBLabel(AllIcons.General.Warning);
    myWarningLabel.setVisible(false);

    myRunnerClassEditor.addFocusListener(new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        validateRunnerClass();
      }
    });

    JPanel runnerPanel = new JPanel(new BorderLayout());
    runnerPanel.add(myRunnerClassEditor, BorderLayout.CENTER);
    runnerPanel.add(myWarningLabel, BorderLayout.EAST);

    JPanel springPanel = new JPanel(new BorderLayout());
    springPanel.add(mySpringPropertiesEditor, BorderLayout.CENTER);

    setupPackagesTable();
    ToolbarDecorator decorator = ToolbarDecorator.createDecorator(myPackagesTable);
    decorator.setAddAction(button -> {
      if (myPackagesTable.isEditing()) {
        myPackagesTable.getCellEditor().stopCellEditing();
      }
      myPackagesModel.addRow("");
      int newRow = myPackagesModel.getRowCount() - 1;
      myPackagesTable.editCellAt(newRow, 0);
      Component editorComponent = myPackagesTable.getEditorComponent();
      if (editorComponent != null) {
        editorComponent.requestFocusInWindow();
      }
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
        .addLabeledComponent("Spring Properties File:", springPanel)
        .addTooltip("Enter the fully qualified name of the default test runner class.")
        .addVerticalGap(10)
        .addLabeledComponent("Scan for step definitions only in these packages (and sub-packages):", decorator.createPanel(), true)
        .addVerticalGap(5)
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
    ColumnInfo<String, String> columnInfo = new ColumnInfo<String, String>("Package") {
      @Nullable
      @Override
      public String valueOf(String s) {
        return s;
      }

      @Override
      public boolean isCellEditable(String s) {
        return true;
      }

    };

    myPackagesModel = new ListTableModel<>(new ColumnInfo[]{columnInfo}, new ArrayList<>());
    myPackagesTable = new JBTable(myPackagesModel);
    myPackagesTable.setShowGrid(false);
    myPackagesTable.setTableHeader(null);
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

  @Override
  public boolean isModified() {
    List<String> uiList = myPackagesModel.getItems();
    List<String> settingsList = mySettingsService.getState().packageNames;
    boolean packagesModified = !new ArrayList<>(uiList).equals(settingsList);

    String uiRunner = myRunnerClassEditor.getText();
    String settingsRunner = StringUtil.notNullize(mySettingsService.getState().cucumberRunner);
    boolean runnerModified = !Objects.equals(uiRunner, settingsRunner);

    String uiSpringProperties = mySpringPropertiesEditor.getText();
    String settingsSpringProperties = StringUtil.notNullize(mySettingsService.getState().springProperties);
    boolean springPropertiesModified = !Objects.equals(uiSpringProperties, settingsSpringProperties);

    return packagesModified || runnerModified || springPropertiesModified;
  }

  @Override
  public void apply() {
    mySettingsService.getState().packageNames = new ArrayList<>(myPackagesModel.getItems());
    mySettingsService.getState().cucumberRunner = myRunnerClassEditor.getText();
    mySettingsService.getState().springProperties = mySpringPropertiesEditor.getText();
  }

  @Override
  public void reset() {
    List<String> packageNames = mySettingsService.getState().packageNames;
    myPackagesModel.setItems(packageNames != null ? new ArrayList<>(packageNames) : new ArrayList<>());

    myRunnerClassEditor.setText(mySettingsService.getState().cucumberRunner);
    validateRunnerClass();
    mySpringPropertiesEditor.setText(mySettingsService.getState().springProperties);
  }

  @Override
  public void disposeUIResources() {
    if (myRefreshTimer != null) {
      myRefreshTimer.stop();
      myRefreshTimer = null;
    }
  }
}
