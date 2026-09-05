package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.enginegame.EngineGameMatchRulesSelection;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;

/** Typical and custom match-rules picker for the engine-game start dialog. */
final class MatchRulesPicker {
  private final JComboBox<Item> combo = new JComboBox<>();
  private KataGoRules selected;
  private boolean applyingProgrammatically;

  MatchRulesPicker() {
    KataGoRules prefill = EngineGameMatchRulesSelection.prefill(Lizzie.config);
    selected = prefill;
    List<Item> items = new ArrayList<>();
    int selectedIndex = 0;
    int index = 0;
    for (String name : KataGoRules.officialPresetNames()) {
      KataGoRules rules = KataGoRules.parse(name).orElseThrow();
      items.add(new Item(displayName(name, rules), rules, false));
      if (rules.semanticallyEquals(prefill)) {
        selectedIndex = index;
      }
      index++;
    }
    boolean matched = items.stream().anyMatch(item -> item.rules.semanticallyEquals(prefill));
    items.add(
        new Item(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.matchRules.custom"),
            prefill,
            true));
    if (!matched) {
      selectedIndex = items.size() - 1;
    }
    for (Item item : items) {
      combo.addItem(item);
    }
    combo.setSelectedIndex(selectedIndex);
    combo.addActionListener(event -> onComboChanged());
    combo.setPreferredSize(new Dimension(180, combo.getPreferredSize().height));
  }

  JComboBox<Item> component() {
    return combo;
  }

  KataGoRules selected() {
    return selected;
  }

  boolean offersCustomChoice() {
    return combo.getItemCount() > 0 && combo.getItemAt(combo.getItemCount() - 1).custom;
  }

  void applyCustom(KataGoRules rules) {
    if (rules == null) {
      return;
    }
    selected = rules;
    applyingProgrammatically = true;
    try {
      int customIndex = combo.getItemCount() - 1;
      Item custom =
          new Item(
              Lizzie.resourceBundle.getString("NewEngineGameDialog.matchRules.custom"),
              rules,
              true);
      combo.removeItemAt(customIndex);
      combo.insertItemAt(custom, customIndex);
      combo.setSelectedIndex(customIndex);
    } finally {
      applyingProgrammatically = false;
    }
  }

  private void onComboChanged() {
    if (applyingProgrammatically) {
      return;
    }
    Item item = (Item) combo.getSelectedItem();
    if (item == null) {
      return;
    }
    if (!item.custom) {
      selected = item.rules;
      return;
    }
    if (GraphicsEnvironment.isHeadless()) {
      selected = item.rules;
      return;
    }
    KataGoRules edited =
        SetKataRules.composeMatchRules(combo.getTopLevelAncestor() instanceof java.awt.Window
                ? (java.awt.Window) combo.getTopLevelAncestor()
                : null,
            selected)
            .orElse(null);
    if (edited != null) {
      applyCustom(edited);
    } else {
      applyingProgrammatically = true;
      try {
        selectItemMatching(selected);
      } finally {
        applyingProgrammatically = false;
      }
    }
  }

  private void selectItemMatching(KataGoRules rules) {
    for (int i = 0; i < combo.getItemCount(); i++) {
      Item item = combo.getItemAt(i);
      if (item.rules.semanticallyEquals(rules) && !item.custom) {
        combo.setSelectedIndex(i);
        return;
      }
    }
    combo.setSelectedIndex(combo.getItemCount() - 1);
  }

  private static String displayName(String preset, KataGoRules rules) {
    return MatchRulesSnapshotName.display(rules);
  }

  private static final class Item {
    private final String label;
    private final KataGoRules rules;
    private final boolean custom;

    private Item(String label, KataGoRules rules, boolean custom) {
      this.label = label;
      this.rules = rules;
      this.custom = custom;
    }

    @Override
    public String toString() {
      return label;
    }
  }
}

final class MatchRulesSnapshotName {
  private MatchRulesSnapshotName() {}

  static String display(KataGoRules rules) {
    return featurecat.lizzie.enginegame.MatchRulesSnapshot.ruleName(
        rules, Lizzie.resourceBundle);
  }
}
