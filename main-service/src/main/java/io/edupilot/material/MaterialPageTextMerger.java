package io.edupilot.material;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MaterialPageTextMerger {

	public String mergeCaption(String text, String caption) {
		if (!StringUtils.hasText(caption)) {
			return text;
		}
		return text + "\n\n[그림 설명] " + caption.trim();
	}
}
