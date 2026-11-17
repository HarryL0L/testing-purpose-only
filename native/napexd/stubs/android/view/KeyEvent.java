package android.view;
public class KeyEvent extends InputEvent {
  public static final int ACTION_DOWN = 0, ACTION_UP = 1;
  public KeyEvent(long downTime, long eventTime, int action, int code, int repeat) {}
  public final void setSource(int source) {}
}
