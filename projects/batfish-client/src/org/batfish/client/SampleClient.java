package org.batfish.client;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.batfish.common.CoordConsts;
import org.batfish.common.CoordConsts.WorkStatusCode;
import org.batfish.common.WorkItem;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.FileDataBodyPart;
import org.glassfish.jersey.uri.UriComponent;

public class SampleClient {

   private String _coordinator;

   public SampleClient(String coordinator, String testrigName,
         String testrigZipfileName) {

      _coordinator = coordinator;

      uploadTestrig(testrigName, testrigZipfileName);

      // send parsing command
      HashMap<String, String> parseRequestParamMap = new HashMap<String, String>();
      parseRequestParamMap.put(CoordConsts.SVC_COMMAND_PARSE_KEY, "");
      parseRequestParamMap.put(CoordConsts.SVC_TESTRIG_NAME_KEY, testrigName);

      UUID parseWorkUUID = queueWork(parseRequestParamMap);

      if (parseWorkUUID == null) {
         return;
      }

      WorkStatusCode status = getWorkStatus(parseWorkUUID);

      while (status != WorkStatusCode.TERMINATEDABNORMALLY
            && status != WorkStatusCode.TERMINATEDNORMALLY) {

         System.out.printf("status: %s\n", status);

         try {
            Thread.sleep(10 * 1000);
         }
         catch (InterruptedException ex) {
            System.err.printf("sleeping interrupted");
            ex.printStackTrace();
            break;
         }

         status = getWorkStatus(parseWorkUUID);
      }

      System.out.printf("final status: %s\n", status);

      // get the results
      getObject("dummytestrigname", "build.xml");

   }

   private WorkStatusCode getWorkStatus(UUID parseWorkUUID) {
      try {
         Client client = ClientBuilder.newClient();
         WebTarget webTarget = client.target(
               String.format("http://%s%s/%s", _coordinator,
                     CoordConsts.SVC_BASE_WORK_MGR,
                     CoordConsts.SVC_WORK_GET_WORKSTATUS_RSC)).queryParam(
               CoordConsts.SVC_WORKID_KEY,
               UriComponent.encode(parseWorkUUID.toString(),
                     UriComponent.Type.QUERY_PARAM_SPACE_ENCODED));
         Response response = webTarget.request(MediaType.APPLICATION_JSON)
               .get();

         System.out.println(response.getStatus() + " "
               + response.getStatusInfo() + " " + response);

         if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            System.err.printf("Did not get an OK response\n");
            return null;            
         }
         
         String sobj = response.readEntity(String.class);
         JSONArray array = new JSONArray(sobj);
         System.out.printf("response: %s [%s] [%s]\n", array.toString(),
               array.get(0), array.get(1));

         if (!array.get(0).equals(CoordConsts.SVC_SUCCESS_KEY)) {
            System.err.printf("got error while checking work status: %s %s\n",
                  array.get(0), array.get(1));
            return null;
         }

         JSONObject jObj = new JSONObject(array.get(1).toString());

         if (!jObj.has(CoordConsts.SVC_WORKSTATUS_KEY)) {
            System.err.printf("workstatus key not found in: %s\n",
                  jObj.toString());
            return null;
         }

         return WorkStatusCode.valueOf(jObj
               .getString(CoordConsts.SVC_WORKSTATUS_KEY));
      }
      catch (ProcessingException e) {
         System.err.printf("unable to connect to %s: %s\n", _coordinator, e
               .getStackTrace().toString());
         return null;
      }
      catch (Exception e) {
         System.err.printf("exception: ");
         e.printStackTrace();
         return null;
      }
   }

   private boolean uploadTestrig(String testrigName, String zipfileName) {
      try {

         Client client = ClientBuilder.newBuilder()
               .register(MultiPartFeature.class).build();
         WebTarget webTarget = client.target(String.format("http://%s%s/%s",
               _coordinator, CoordConsts.SVC_BASE_WORK_MGR,
               CoordConsts.SVC_WORK_UPLOAD_TESTRIG_RSC));

         MultiPart multiPart = new MultiPart();
         multiPart.setMediaType(MediaType.MULTIPART_FORM_DATA_TYPE);

         FormDataBodyPart testrigNameBodyPart = new FormDataBodyPart(
               CoordConsts.SVC_TESTRIG_NAME_KEY, testrigName,
               MediaType.TEXT_PLAIN_TYPE);
         multiPart.bodyPart(testrigNameBodyPart);

         FileDataBodyPart fileDataBodyPart = new FileDataBodyPart(
               CoordConsts.SVC_TESTRIG_ZIPFILE_KEY, new File(zipfileName),
               MediaType.APPLICATION_OCTET_STREAM_TYPE);
         multiPart.bodyPart(fileDataBodyPart);

         Response response = webTarget.request(MediaType.APPLICATION_JSON)
               .post(Entity.entity(multiPart, multiPart.getMediaType()));

         System.out.println(response.getStatus() + " "
               + response.getStatusInfo() + " " + response);

         if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            System.err.printf("UploadTestrig: Did not get an OK response\n");
            return false;            
         }
         
         String sobj = response.readEntity(String.class);
         JSONArray array = new JSONArray(sobj);
         System.out.printf("response: %s [%s] [%s]\n", array.toString(),
               array.get(0), array.get(1));

         if (!array.get(0).equals(CoordConsts.SVC_SUCCESS_KEY)) {
            System.err.printf("got error while uploading test rig: %s %s\n",
                  array.get(0), array.get(1));
            return false;
         }

         return true;
      }
      catch (Exception e) {
         System.err.printf(
               "Exception when uploading test rig to %s using (%s, %s)\n",
               _coordinator, testrigName, zipfileName);
         e.printStackTrace();
         return false;
      }
   }

   private UUID queueWork(Map<String, String> requestParamMap) {

      WorkItem wItem = new WorkItem();

      for (String key : requestParamMap.keySet()) {
         wItem.addRequestParam(key, requestParamMap.get(key));
      }

      try {
         Client client = ClientBuilder.newClient();
         WebTarget webTarget = client.target(
               String.format("http://%s%s/%s", _coordinator,
                     CoordConsts.SVC_BASE_WORK_MGR,
                     CoordConsts.SVC_WORK_QUEUE_WORK_RSC)).queryParam(
               CoordConsts.SVC_WORKITEM_KEY,
               UriComponent.encode(wItem.toJsonString(),
                     UriComponent.Type.QUERY_PARAM_SPACE_ENCODED));
         Response response = webTarget.request(MediaType.APPLICATION_JSON)
               .get();

         if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            System.err.printf("QueueWork: Did not get an OK response\n");
            return null;            
         }
         
         String sobj = response.readEntity(String.class);
         JSONArray array = new JSONArray(sobj);
         System.out.printf("response: %s [%s] [%s]\n", array.toString(),
               array.get(0), array.get(1));

         if (!array.get(0).equals(CoordConsts.SVC_SUCCESS_KEY)) {
            System.err.printf("got error while queuing work: %s %s\n",
                  array.get(0), array.get(1));
            return null;
         }

         return wItem.getId();
      }
      catch (ProcessingException e) {
         System.err.printf("unable to connect to %s: %s\n", _coordinator, e
               .getStackTrace().toString());
         return null;
      }
      catch (Exception e) {
         System.err.printf("exception: ");
         e.printStackTrace();
         return null;
      }
   }

   private boolean getObject(String testrigName, String zipfileName) {
      try {

         Client client = ClientBuilder.newBuilder()
               .register(MultiPartFeature.class).build();
         WebTarget webTarget = client.target(
               String.format("http://%s%s/%s", _coordinator,
                     CoordConsts.SVC_BASE_WORK_MGR,
                     CoordConsts.SVC_WORK_GET_OBJECT_RSC)).queryParam(
               CoordConsts.SVC_WORK_OBJECT_KEY, zipfileName);

         Response response = webTarget.request(
               MediaType.APPLICATION_OCTET_STREAM).get();

         System.out.println(response.getStatus() + " "
               + response.getStatusInfo() + " " + response);

         if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            System.err.printf("GetObject: Did not get an OK response\n");
            return false;            
         }

         File inFile = response.readEntity(File.class);
         File outFile = new File(zipfileName + ".zip");

         inFile.renameTo(outFile);

         FileWriter fr = new FileWriter(inFile);
         fr.flush();
         fr.close();

         return true;
      }
      catch (Exception e) {
         System.err.printf(
               "Exception when uploading test rig to %s using (%s, %s)\n",
               _coordinator, testrigName, zipfileName);
         e.printStackTrace();
         return false;
      }
   }
}