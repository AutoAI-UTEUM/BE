package io.edupilot.admin.xai;

import org.springframework.data.jpa.repository.JpaRepository;

public interface XaiAlertConfigRepository
	extends JpaRepository<XaiAlertConfig, Byte> {
}
