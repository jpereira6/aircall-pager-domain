package com.jp.aircall.domain.model.policy;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class SmsTarget implements Target {

  private final String phone;

  @Override
  public String getValue() {
    return this.phone;
  }

  @Override
  public TARGET_TYPE getType() {
    return TARGET_TYPE.SMS;
  }
}
