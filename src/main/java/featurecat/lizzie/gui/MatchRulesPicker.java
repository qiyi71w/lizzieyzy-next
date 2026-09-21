package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.enginegame.EngineGameMatchRulesSelection;
import featurecat.lizzie.enginegame.MatchRuleOption;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import javax.swing.JComboBox;

/** Typical and custom match-rules picker for the engine-game start dialog. */
final class MatchRulesPicker {
  private final JComboBox<Item> combo = new JComboBox<>();
  private KataGoRules selected;
  private boolean applyingProgrammatically;

  MatchRulesPicker() {
    KataGoRules prefill = EngineGameMatchRulesSelection.prefill(Lizzie.config);
    selected = prefill;
    for (MatchRuleOption option : MatchRuleOption.options()) {
      combo.addItem(new Item(option.displayName(Lizzie.resourceBundle), option.rules(), false));
    }
    combo.addItem(
        new Item(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.matchRules.custom"),
            prefill,
            true));
    selectItemMatching(prefill);
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
      selectItemMatching(rules);
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
        SetKataRules.composeMatchRules(
                combo.getTopLevelAncestor() instanceof java.awt.Window
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
    MatchRuleOption option = MatchRuleOption.matching(rules);
    combo.setSelectedIndex(
        option == null ? combo.getItemCount() - 1 : MatchRuleOption.options().indexOf(option));
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
