package com.jp.aircall.domain.adapters;

import com.jp.aircall.domain.model.policy.EscalationPolicy;
import java.util.Optional;

public interface EPAdapter {

  Optional<EscalationPolicy> getEpByServiceId(String serviceId);
}
