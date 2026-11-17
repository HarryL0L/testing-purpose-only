package android.view;
public final class MotionEvent extends InputEvent {
  public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3;
  public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, int metaState) { throw new RuntimeException("stub"); }
  public void setSource(int source) {}
  public void recycle() {}
}
