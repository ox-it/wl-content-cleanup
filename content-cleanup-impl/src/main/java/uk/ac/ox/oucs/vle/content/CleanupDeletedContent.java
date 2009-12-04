package uk.ac.ox.oucs.vle.content;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.InUseException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.TypeException;
import org.sakaiproject.time.api.Time;
import org.sakaiproject.time.api.TimeService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;

/**
 * Job to remove old delete content from content hosting. This is in a seperate
 * project as the kernel (content hosting) can't bind to stuff outside it.
 * 
 * @author buckett
 * 
 */
public class CleanupDeletedContent implements Job {

	private static final Log log = LogFactory
			.getLog(CleanupDeletedContent.class);

	private ContentHostingService chs;
	private ServerConfigurationService scs;
	private TimeService ts;
	private SessionManager sm;

	public void setContentHostingService(ContentHostingService chs) {
		this.chs = chs;
	}

	public void setServerConfigurationService(ServerConfigurationService scs) {
		this.scs = scs;
	}

	public void setTimeService(TimeService ts) {
		this.ts = ts;
	}
	
	public void setSessionManager(SessionManager sm) {
		this.sm = sm;
	}

	public void execute(JobExecutionContext context)
	throws JobExecutionException {
		Session sakaiSession = sm.getCurrentSession();
		sakaiSession.setUserId("admin");
		sakaiSession.setUserEid("admin");

		List<ContentResource> deleted = (List<ContentResource>) chs
				.getAllDeletedResources("/");
		long daysToKeep = scs.getInt("keep.deleted.files.days", 30);
		Time oldest = ts.newTime(System.currentTimeMillis()
				- (daysToKeep * 1000 * 60 * 60 * 24));
		log.info("Looking at " + deleted.size()
				+ " resources, and removing anything older than: " + oldest.toStringLocalDate());
		int removed = 0, attempted = 0;
		long totalSize = 0, removedSize = 0;
		for (ContentResource resource : deleted) {
			// We can't get at the deleted field in the DB but the modification
			// time is updated when it's deleted.
			Object property = resource.getProperties().get(ResourceProperties.PROP_MODIFIED_DATE);
			long size = resource.getContentLength();
			totalSize += size;
			if (property != null && property instanceof String) {
				Time time = ts.newTimeGmt((String)property);
				if (oldest.after(time)) {
					try {
						attempted++;
						chs.removeDeletedResource(resource.getId());
						removed++;
						removedSize += size;
					} catch (PermissionException e) {
						log.warn("Failed to remove due to lack of permission: "
								+ resource.getId());
					} catch (IdUnusedException e) {
						log
								.warn("Failed to remove due to not beging able to find: "
										+ resource.getId());
					} catch (TypeException e) {
						log.warn("Failed to remove due to type exception: "
								+ resource.getId());
					} catch (InUseException e) {
						log.warn("Failed to remove due resource being in use: "
								+ resource.getId());
					}
				}
			}
		}
		int failed = attempted - removed;
		log.info("Out of " + deleted.size() + "(~"+ formatSize(totalSize)+ ") "
				+ "deleted resources, successfully removed " + removed + "(~"+ formatSize(removedSize)+ ")"
				+ ((failed > 0) ? ", failed on " + failed + " resources": ""));
	}
	
	static String formatSize(long size) {
		if (size >= 1L<<30) {
			return ""+ (size / 1L>>30)+ "Gb";
		} else if (size >= 1L<<20) {
			return ""+ (size / 1L>>20)+ "Mb";
		} else if (size >= 1L<<10) {
			return ""+ (size / 1L>>10)+ "kb";
		} else {
			return ""+ size+ "b";
		}
	}

}
