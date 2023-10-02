package com.jp.aircall.domain.model.policy;

public interface Target {

  enum TARGET_TYPE {SMS, EMAIL}

  String getValue();
  TARGET_TYPE getType();


}
