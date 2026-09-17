package featurecat.lizzie.gui;

import java.awt.event.InputMethodEvent;
import java.awt.event.KeyEvent;
import java.text.AttributedCharacterIterator;

/** Tracks one physical Enter separately from an input method's commit notification. */
final class SearchInputSession {
  private boolean composing;
  private boolean committedSinceRelease;
  private long committedAt;
  private boolean enterArmed;

  void textChanged(InputMethodEvent event) {
    AttributedCharacterIterator text = event.getText();
    int length = text == null ? 0 : text.getEndIndex() - text.getBeginIndex();
    composing = length > event.getCommittedCharacterCount();
    if (event.getCommittedCharacterCount() > 0) {
      committedSinceRelease = true;
      committedAt = event.getWhen();
      enterArmed = false;
    }
  }

  boolean isComposing() {
    return composing;
  }

  /** Called after the platform input method has had the key; activation waits for release. */
  boolean enter(KeyEvent event) {
    if (event.getID() == KeyEvent.KEY_PRESSED) {
      // A later physical press follows a completed Space/mouse commit. The Enter that
      // caused a commit retains its original timestamp even if delivered after the commit.
      if (committedSinceRelease && event.getWhen() > committedAt) committedSinceRelease = false;
      enterArmed = !composing && !committedSinceRelease && event.getModifiersEx() == 0;
      return false;
    }
    if (event.getID() == KeyEvent.KEY_RELEASED) {
      boolean activate = enterArmed && !composing && !committedSinceRelease;
      enterArmed = false;
      committedSinceRelease = false;
      return activate;
    }
    return false;
  }
}
