package android.view;
public final class MotionEvent extends InputEvent {
  public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3;
  public static final int TOOL_TYPE_FINGER = 1;
  public static final class PointerProperties { public int id; public int toolType; }
  public static final class PointerCoords { public float x, y, pressure, size; }
  public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, int metaState) { throw new RuntimeException("stub"); }
  public static MotionEvent obtain(long downTime, long eventTime, int action, int pointerCount, PointerProperties[] pointerProperties, PointerCoords[] pointerCoords, int metaState, int buttonState, float xPrecision, float yPrecision, int deviceId, int edgeFlags, int source, int flags) { throw new RuntimeException("stub"); }
  public void setSource(int source) {}
  public void recycle() {}
}
