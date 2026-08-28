package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class InFrameLeftoverDragHandlesTest {

  @Test
  void overlayHandlesDoNotTakeKeyboardFocus() {
    InFrameLeftoverDragHandles handles = new InFrameLeftoverDragHandles(null);
    assertFalse(handles.anyHandleCanReceiveFocus());
  }
}
