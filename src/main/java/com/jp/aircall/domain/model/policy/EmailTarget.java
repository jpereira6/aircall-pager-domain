package com.jp.aircall.domain.model.policy;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class EmailTarget implements Target{


  private final String email;

  @Override
  public String getValue() {
    return this.email;
  }

  @Override
  public TARGET_TYPE getType() {
    return TARGET_TYPE.EMAIL;
  }
}
