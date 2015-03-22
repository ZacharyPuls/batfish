package org.batfish.coordinator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.logging.log4j.Logger;
import org.batfish.common.BfConsts;
import org.batfish.common.CoordConsts;
import org.batfish.common.WorkItem;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.glassfish.jersey.uri.UriComponent;

public class WorkMgr {

   private WorkQueueMgr _workQueueMgr;
   private Logger _logger;

   public WorkMgr() {
      _logger = Main.initializeLogger();
      _workQueueMgr = new WorkQueueMgr();

      Runnable assignWorkTask = new AssignWorkTask();
      Executors.newScheduledThreadPool(1).scheduleWithFixedDelay(
            assignWorkTask, 0, Main.getSettings().getPeriodAssignWorkMs(),
            TimeUnit.MILLISECONDS);

      Runnable checkWorkTask = new CheckTaskTask();
      Executors.newScheduledThreadPool(1)
            .scheduleWithFixedDelay(checkWorkTask, 0,
                  Main.getSettings().getPeriodCheckWorkMs(),
                  TimeUnit.MILLISECONDS);
   }

   public JSONObject getStatusJson() throws JSONException {
      return _workQueueMgr.getStatusJson();
   }

   public boolean queueWork(WorkItem workItem) throws Exception {

      boolean success = _workQueueMgr.queueUnassignedWork(new QueuedWork(workItem));

      // as an optimization trigger AssignWork to see if we can schedule this
      // (or another) work
      if (success) {
         Thread thread = new Thread() {
            public void run() {
               AssignWork();
            }
         };
         thread.start();
      }

      return success;
   }

   private void AssignWork() {

      _logger.info("WM:AssignWork entered\n");

      QueuedWork work = _workQueueMgr.getWorkForAssignment();

      // get out if no work was found
      if (work == null) {
         _logger.info("WM:AssignWork: No unassigned work\n");
         return;
      }

      String idleWorker = Main.getPoolMgr().getWorkerForAssignment();

      // get out if no idle worker was found, but release the work first
      if (idleWorker == null) {
         _workQueueMgr.markAssignmentFailure(work);

         _logger.info("WM:AssignWork: No idle worker\n");
         return;
      }

      AssignWork(work, idleWorker);
   }

   private void AssignWork(QueuedWork work, String worker) {

      _logger.info("WM:AssignWork: Trying to assign " + work + " to " + worker + " \n");

      boolean assigned = false;
      
      try {
         
         //get the task and add other standard stuff
         JSONObject task = work.getWorkItem().toTask();
         task.put("datadir", Main.getSettings().getTestrigStorageLocation());
         task.put("logfile", work.getId().toString() + ".log");
         
         Client client = ClientBuilder.newClient();
         WebTarget webTarget = client.target(String.format("http://%s%s/%s", worker,
                     BfConsts.SVC_BASE_RSC, BfConsts.SVC_RUN_TASK_RSC))
              .queryParam(BfConsts.SVC_TASKID_KEY,
                   UriComponent.encode(work.getId().toString(), UriComponent.Type.QUERY_PARAM_SPACE_ENCODED))
              .queryParam(BfConsts.SVC_TASKID_KEY, 
                    UriComponent.encode(task.toString(), UriComponent.Type.QUERY_PARAM_SPACE_ENCODED));

         Response response = webTarget
               .request(MediaType.APPLICATION_JSON)
               .get();

         if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            _logger.error("WM:AssignWork: Got non-OK response "
                  + response.getStatus() + "\n");
         }
         else {
            String sobj = response.readEntity(String.class);
            JSONArray array = new JSONArray(sobj);
            _logger.info(String.format(
                  "WM:AssignWork: response: %s [%s] [%s]\n", array.toString(),
                  array.get(0), array.get(1)));

            if (!array.get(0).equals(BfConsts.SVC_SUCCESS_KEY)) {
               _logger.error(String.format("ERROR in assigning task: %s %s\n",
                     array.get(0), array.get(1)));
            }
            else {
               assigned = true;
            }
         }
      }
      catch (ProcessingException e) {
         String stackTrace = ExceptionUtils.getFullStackTrace(e);
         _logger.error(String.format("unable to connect to %s: %s\n", worker, stackTrace));
      }
      catch (Exception e) {
         String stackTrace = ExceptionUtils.getFullStackTrace(e);
         _logger.error(String.format("exception: %s\n", stackTrace));
      }
      
      // mark the assignment results accordingly
      if (assigned) {
         _workQueueMgr.markAssignmentSuccess(work, worker);
      }
      else {
         _workQueueMgr.markAssignmentFailure(work);
      }
      
      Main.getPoolMgr().markAssignmentResult(worker, assigned);
   }

   private void checkTask() {

      _logger.info("WM:checkTask entered\n");

      QueuedWork work = _workQueueMgr.getWorkForChecking();

      if (work == null) {
         _logger.info("WM:checkTask: No assigned work\n");
         return;
      }

      String assignedWorker = work.getAssignedWorker();

      if (assignedWorker == null) {
         _logger.error("WM:CheckWork no assinged worker for " + work + "\n");
         _workQueueMgr.makeWorkUnassigned(work);
         return;
      }

      checkTask(work, assignedWorker);
   }

   private void checkTask(QueuedWork work, String worker) {
      _logger.info("WM:CheckWork: Trying to check " + work + " on " + worker + " \n");

      BfConsts.TaskStatus status = BfConsts.TaskStatus.UnreachableOrBadResponse;

      try {
         Client client = ClientBuilder.newClient();
         WebTarget webTarget = client.target(String.format("http://%s%s/%s",
               worker, BfConsts.SVC_BASE_RSC,
               BfConsts.SVC_GET_TASKSTATUS_RSC))
               .queryParam(BfConsts.SVC_TASKID_KEY, 
                     UriComponent.encode(work.getId().toString(), UriComponent.Type.QUERY_PARAM_SPACE_ENCODED));
         Response response = webTarget
               .request(MediaType.APPLICATION_JSON)
               .get();

         if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            _logger.error("WM:CheckTask: Got non-OK response "
                  + response.getStatus() + "\n");
         }
         else {
            String sobj = response.readEntity(String.class);
            JSONArray array = new JSONArray(sobj);
            _logger.info(String.format("response: %s [%s] [%s]\n",
                  array.toString(), array.get(0), array.get(1)));

            if (!array.get(0).equals(BfConsts.SVC_SUCCESS_KEY)) {
               _logger.error(String.format(
                     "got error while refreshing status: %s %s\n",
                     array.get(0), array.get(1)));
            }
            else {

               JSONObject jObj = new JSONObject(array.get(1).toString());

               if (!jObj.has("status")) {
                  _logger.error(String
                        .format("did not see status key in json response\n"));
               }
               else {
                  status = BfConsts.TaskStatus.valueOf(jObj
                        .getString("status"));
               }
            }
         }
      }
      catch (ProcessingException e) {
         String stackTrace = ExceptionUtils.getFullStackTrace(e);
         _logger.error(String.format("unable to connect to %s: %s\n", worker, stackTrace));
      }
      catch (Exception e) {
         String stackTrace = ExceptionUtils.getFullStackTrace(e);
         _logger.error(String.format("exception: %s\n", stackTrace));
      }
      
      _workQueueMgr.processStatusCheckResult(work, status);
   }

   public void uploadTestrig(String name, InputStream fileStream)
         throws Exception {

      File testrigDir = new File(Main.getSettings().getTestrigStorageLocation()
            + "/" + name);

      if (testrigDir.exists()) {
         throw new Exception("test rig with the same name exists");
      }

      if (!testrigDir.mkdirs()) {
         throw new Exception("failed to create directory "
               + testrigDir.getAbsolutePath());
      }

      try (OutputStream fileOutputStream = new FileOutputStream(
            testrigDir.getAbsolutePath() + "/" + name + ".zip")) {
         int read = 0;
         final byte[] bytes = new byte[1024];
         while ((read = fileStream.read(bytes)) != -1) {
            fileOutputStream.write(bytes, 0, read);
         }
      }
   }

   public QueuedWork getWork(UUID workItemId) {
      return _workQueueMgr.getWork(workItemId);
   }

   public File getObject(String objectName) {
      File file = new File(Main.getSettings().getTestrigStorageLocation() + "/"
            + objectName);

      if (file.isFile()) {
         return file;
      }

      return null;
   }

   final class AssignWorkTask implements Runnable {
      @Override
      public void run() {
         Main.getWorkMgr().AssignWork();
      }
   }

   final class CheckTaskTask implements Runnable {
      @Override
      public void run() {
         Main.getWorkMgr().checkTask();
      }
   }
}