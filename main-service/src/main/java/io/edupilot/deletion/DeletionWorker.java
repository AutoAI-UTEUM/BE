package io.edupilot.deletion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;
import io.edupilot.ai.AiClient;
import io.edupilot.material.storage.FileStorage;

@Component
public class DeletionWorker {
	private static final Logger log=LoggerFactory.getLogger(DeletionWorker.class);
	private final DeletionTaskStore store;
	private final FileStorage files;
	private final AiClient ai;
	private final DeletionProperties policy;
	private final Executor executor;
	public DeletionWorker(DeletionTaskStore store,FileStorage files,AiClient ai,DeletionProperties policy,
		@Qualifier("deletionExecutor") Executor executor) {
		this.store=store;this.files=files;this.ai=ai;this.policy=policy;this.executor=executor;
	}
	@Scheduled(fixedDelayString="${edupilot.deletion.poll-delay-ms:30000}")
	public void tick() {
		if(!policy.enabled()) { return; }
		try { for(Long id:store.candidates()) { executor.execute(()->process(id)); } }
		catch(RuntimeException failure) { log.warn("Deletion journal polling unavailable"); }
	}
	public void process(Long id) {
		try { processClaim(id); }
		catch(RuntimeException failure) {
			// Pending/leased metadata survives DB/provider outages; never log provider or persistence details.
			log.atWarn().addKeyValue("deletionTaskId",id).log("Deletion task processing unavailable");
		}
	}
	private void processClaim(Long id) {
		DeletionClaim claim=store.claim(id);
		if(claim==null||!store.mayExecute(claim)) { return; }
		// No database transaction remains open across filesystem/provider operations.
		try {
			switch(claim.kind()) {
				case ORIGINAL_PDF -> files.delete(claim.resourceKey());
				case AVATAR -> files.delete(claim.resourceKey());
				case RENDERED_PAGES -> files.deleteMaterialRenders(claim.resourceKey());
				case EXTERNAL_AI -> ai.deleteFile(claim.resourceKey());
				case ACCOUNT -> throw new IllegalStateException("Account tombstones are not physical cleanup tasks");
			}
			store.complete(claim);
		} catch(RuntimeException failure) {
			store.fail(claim);
			log.atWarn().addKeyValue("deletionTaskId",id).log("Deletion task retry recorded");
		}
	}
}
