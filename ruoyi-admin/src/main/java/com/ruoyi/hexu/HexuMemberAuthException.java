package com.ruoyi.hexu;

/** An absent or expired member session is an authentication failure, not bad input. */
public class HexuMemberAuthException extends RuntimeException {
    public HexuMemberAuthException(String message) { super(message); }
}
