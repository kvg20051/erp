package ru.nixu.core;

public class ApiError extends RuntimeException {
  public final int status;

  public ApiError(int status, String message) {
    super(message);
    this.status = status;
  }

  public static ApiError bad(String s) {
    return new ApiError(422, s);
  }

  public static void require(boolean ok, String message) {
    if (!ok) throw bad(message);
  }
}
