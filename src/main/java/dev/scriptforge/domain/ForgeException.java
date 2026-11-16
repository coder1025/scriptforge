package dev.scriptforge.domain;

public class ForgeException extends RuntimeException {
  private final String code;

  public ForgeException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static void require(boolean condition, String code, String message) {
    if (!condition) throw new ForgeException(code, message);
  }
}
