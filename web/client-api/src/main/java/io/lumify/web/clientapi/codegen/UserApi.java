package io.lumify.web.clientapi.codegen;

import io.lumify.web.clientapi.codegen.ApiException;
import io.lumify.web.clientapi.ApiInvoker;

import io.lumify.web.clientapi.model.ClientApiUsers;
import io.lumify.web.clientapi.model.ClientApiUser;
import com.sun.jersey.multipart.FormDataMultiPart;

import javax.ws.rs.core.MediaType;

import java.io.File;
import java.util.*;

public class UserApi {
  protected String basePath = "http://lumify-dev:8889";
  protected ApiInvoker apiInvoker = ApiInvoker.getInstance();

  public ApiInvoker getInvoker() {
    return apiInvoker;
  }
  
  public void setBasePath(String basePath) {
    this.basePath = basePath;
  }
  
  public String getBasePath() {
    return basePath;
  }

  public ClientApiUser getMe () throws ApiException {
    Object postBody = null;
    // create path and map variables
    String path = "/user/me".replaceAll("\\{format\\}","json");

    // query params
    Map<String, String> queryParams = new HashMap<String, String>();
    Map<String, String> headerParams = new HashMap<String, String>();
    Map<String, String> formParams = new HashMap<String, String>();

    String[] contentTypes = {
      "application/json"};

    String contentType = contentTypes.length > 0 ? contentTypes[0] : "application/json";

    if(contentType.startsWith("multipart/form-data")) {
      boolean hasFields = false;
      FormDataMultiPart mp = new FormDataMultiPart();
      if(hasFields)
        postBody = mp;
    }
    else {
      }

    try {
      String response = apiInvoker.invokeAPI(basePath, path, "GET", queryParams, postBody, headerParams, formParams, contentType);
      if(response != null){
        return (ClientApiUser) ApiInvoker.deserialize(response, "", ClientApiUser.class);
      }
      else {
        return null;
      }
    } catch (ApiException ex) {
      if(ex.getCode() == 404) {
      	return null;
      }
      else {
        throw ex;
      }
    }
  }
  public ClientApiUsers getAll (String q, String workspaceId) throws ApiException {
    Object postBody = null;
    // create path and map variables
    String path = "/user/all".replaceAll("\\{format\\}","json");

    // query params
    Map<String, String> queryParams = new HashMap<String, String>();
    Map<String, String> headerParams = new HashMap<String, String>();
    Map<String, String> formParams = new HashMap<String, String>();

    if(!"null".equals(String.valueOf(q)))
      queryParams.put("q", String.valueOf(q));
    if(!"null".equals(String.valueOf(workspaceId)))
      queryParams.put("workspaceId", String.valueOf(workspaceId));
    String[] contentTypes = {
      "application/json"};

    String contentType = contentTypes.length > 0 ? contentTypes[0] : "application/json";

    if(contentType.startsWith("multipart/form-data")) {
      boolean hasFields = false;
      FormDataMultiPart mp = new FormDataMultiPart();
      if(hasFields)
        postBody = mp;
    }
    else {
      }

    try {
      String response = apiInvoker.invokeAPI(basePath, path, "GET", queryParams, postBody, headerParams, formParams, contentType);
      if(response != null){
        return (ClientApiUsers) ApiInvoker.deserialize(response, "", ClientApiUsers.class);
      }
      else {
        return null;
      }
    } catch (ApiException ex) {
      if(ex.getCode() == 404) {
      	return null;
      }
      else {
        throw ex;
      }
    }
  }
  public ClientApiUsers getManyByIds (List<String> userIds) throws ApiException {
    Object postBody = null;
    // verify required params are set
    if(userIds == null ) {
       throw new ApiException(400, "missing required params");
    }
    // create path and map variables
    String path = "/user/all".replaceAll("\\{format\\}","json");

    // query params
    Map<String, String> queryParams = new HashMap<String, String>();
    Map<String, String> headerParams = new HashMap<String, String>();
    Map<String, String> formParams = new HashMap<String, String>();

    String[] contentTypes = {
      "multipart/form-data"};

    String contentType = contentTypes.length > 0 ? contentTypes[0] : "application/json";

    if(contentType.startsWith("multipart/form-data")) {
      boolean hasFields = false;
      FormDataMultiPart mp = new FormDataMultiPart();
      hasFields = true;
      if(userIds != null) { for(String userId:userIds) { mp.field("userIds[]", userId, MediaType.MULTIPART_FORM_DATA_TYPE); } }
      if(hasFields && !mp.getFields().isEmpty())
        postBody = mp;
    }
    else {
      throw new java.lang.RuntimeException("invalid content type");}

    try {
      String response = apiInvoker.invokeAPI(basePath, path, "POST", queryParams, postBody, headerParams, formParams, contentType);
      if(response != null){
        return (ClientApiUsers) ApiInvoker.deserialize(response, "", ClientApiUsers.class);
      }
      else {
        return null;
      }
    } catch (ApiException ex) {
      if(ex.getCode() == 404) {
      	return null;
      }
      else {
        throw ex;
      }
    }
  }
  public ClientApiUser getByUserName (String username) throws ApiException {
    Object postBody = null;
    // verify required params are set
    if(username == null ) {
       throw new ApiException(400, "missing required params");
    }
    // create path and map variables
    String path = "/user".replaceAll("\\{format\\}","json");

    // query params
    Map<String, String> queryParams = new HashMap<String, String>();
    Map<String, String> headerParams = new HashMap<String, String>();
    Map<String, String> formParams = new HashMap<String, String>();

    if(!"null".equals(String.valueOf(username)))
      queryParams.put("user-name", String.valueOf(username));
    String[] contentTypes = {
      "application/json"};

    String contentType = contentTypes.length > 0 ? contentTypes[0] : "application/json";

    if(contentType.startsWith("multipart/form-data")) {
      boolean hasFields = false;
      FormDataMultiPart mp = new FormDataMultiPart();
      if(hasFields)
        postBody = mp;
    }
    else {
      }

    try {
      String response = apiInvoker.invokeAPI(basePath, path, "GET", queryParams, postBody, headerParams, formParams, contentType);
      if(response != null){
        return (ClientApiUser) ApiInvoker.deserialize(response, "", ClientApiUser.class);
      }
      else {
        return null;
      }
    } catch (ApiException ex) {
      if(ex.getCode() == 404) {
      	return null;
      }
      else {
        throw ex;
      }
    }
  }
  }

