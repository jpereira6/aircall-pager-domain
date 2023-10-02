package com.jp.aircall.domain.model.policy;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class Service {

  public enum SERVICE_STATUS {HEALTHY, UNHEALTHY}

  @NotEmpty private String id;
  private String description;
  private SERVICE_STATUS status;

  public Service(String id, SERVICE_STATUS status) {
    this.id = id;
    this.status = status;
  }
}
