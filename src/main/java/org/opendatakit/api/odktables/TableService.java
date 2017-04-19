/*
 * Copyright (C) 2012-2013 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.api.odktables;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.lang3.CharEncoding;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.opendatakit.aggregate.odktables.rest.ApiConstants;
import org.opendatakit.aggregate.odktables.rest.RFC4180CsvReader;
import org.opendatakit.aggregate.odktables.rest.RFC4180CsvWriter;
import org.opendatakit.aggregate.odktables.rest.entity.Column;
import org.opendatakit.aggregate.odktables.rest.entity.PropertyEntryJson;
import org.opendatakit.aggregate.odktables.rest.entity.PropertyEntryJsonList;
import org.opendatakit.aggregate.odktables.rest.entity.PropertyEntryXml;
import org.opendatakit.aggregate.odktables.rest.entity.PropertyEntryXmlList;
import org.opendatakit.aggregate.odktables.rest.entity.TableDefinition;
import org.opendatakit.aggregate.odktables.rest.entity.TableEntry;
import org.opendatakit.aggregate.odktables.rest.entity.TableResource;
import org.opendatakit.aggregate.odktables.rest.entity.TableResourceList;
import org.opendatakit.aggregate.odktables.rest.entity.TableRole.TablePermission;
import org.opendatakit.api.odktables.TableService;
import org.opendatakit.constants.ServletConsts;
import org.opendatakit.context.CallingContext;
import org.opendatakit.odktables.ConfigFileChangeDetail;
import org.opendatakit.odktables.FileContentInfo;
import org.opendatakit.odktables.FileManager;
import org.opendatakit.odktables.TableManager;
import org.opendatakit.odktables.TableManager.WebsafeTables;
import org.opendatakit.odktables.exception.AppNameMismatchException;
import org.opendatakit.odktables.exception.FileNotFoundException;
import org.opendatakit.odktables.exception.PermissionDeniedException;
import org.opendatakit.odktables.exception.SchemaETagMismatchException;
import org.opendatakit.odktables.exception.TableAlreadyExistsException;
import org.opendatakit.odktables.exception.TableNotFoundException;
import org.opendatakit.odktables.security.TablesUserPermissions;
import org.opendatakit.odktables.security.TablesUserPermissionsImpl;
import org.opendatakit.persistence.QueryResumePoint;
import org.opendatakit.persistence.exception.ODKDatastoreException;
import org.opendatakit.persistence.exception.ODKEntityNotFoundException;
import org.opendatakit.persistence.exception.ODKTaskLockException;
import org.opendatakit.security.common.GrantedAuthorityName;
import org.opendatakit.security.server.SecurityServiceUtil;
import org.opendatakit.utils.WebUtils;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TableService {
  public static final String CURSOR_PARAMETER = "cursor";
  public static final String FETCH_LIMIT = "fetchLimit";
  public static final String OFFICE_ID = "officeId";
  public static final String ERROR_TABLE_NOT_FOUND = "Table not found";
  
  private static final Log logger = LogFactory.getLog(TableService.class);

  private static final ObjectMapper mapper = new ObjectMapper();

  private static final String ERROR_SCHEMA_DIFFERS = "SchemaETag differs";

  private final ServletContext sc;
  private final HttpServletRequest req;
  private final HttpHeaders headers;
  private final UriInfo info;
  private final String appId;
  private final String tableId;
  private final CallingContext callingContext;

  public TableService(ServletContext sc, HttpServletRequest req, HttpHeaders headers,
      UriInfo info, String appId, CallingContext cc) throws ODKEntityNotFoundException,
      ODKDatastoreException {
    this.sc = sc;
    this.req = req;
    this.headers = headers;
    this.info = info;
    this.appId = appId;
    tableId = null;
    this.callingContext = cc;
  }

  public TableService(ServletContext sc, HttpServletRequest req, HttpHeaders headers,
      UriInfo info, String appId, String tableId, CallingContext cc)
      throws ODKEntityNotFoundException, ODKDatastoreException {
    this.sc = sc;
    this.req = req;
    this.headers = headers;
    this.info = info;
    this.appId = appId;
    this.tableId = tableId;
    this.callingContext = cc;
  }


  /**
   *
   * Get all tables on the server. Invoked from OdkTables implementation class.
   *
   * @return {@link TableResourceList} of all tables the user has access to.
   * @throws ODKDatastoreException
   * @throws ODKTaskLockException
   * @throws PermissionDeniedException
   */
  public Response getTables(@QueryParam(CURSOR_PARAMETER) String cursor,
      @QueryParam(FETCH_LIMIT) String fetchLimit, @QueryParam(OFFICE_ID) String officeId) throws ODKDatastoreException,
      PermissionDeniedException, ODKTaskLockException {

    TablesUserPermissions userPermissions = new TablesUserPermissionsImpl(callingContext);

    TableManager tm = new TableManager(appId, userPermissions, callingContext);

    int limit = (fetchLimit == null || fetchLimit.length() == 0) ?
    		2000 : Integer.valueOf(fetchLimit);
    WebsafeTables websafeResult = tm.getTables(
        QueryResumePoint.fromWebsafeCursor(WebUtils.safeDecode(cursor)), limit, officeId);
    ArrayList<TableResource> resources = new ArrayList<TableResource>();
    for (TableEntry entry : websafeResult.tables) {
      // database cruft will have a null schemaETag -- ignore those
      if (entry.getSchemaETag() != null) {
        TableResource resource = getResource(info, appId, entry);

        // set the table-level manifest ETag if known...
        try {
          resource.setTableLevelManifestETag(FileManifestService.getTableLevelManifestETag(
              entry.getTableId(), callingContext));
        } catch (ODKDatastoreException e) {
          // ignore
        }

        resources.add(resource);
      }
    }
    TableResourceList tableResourceList = new TableResourceList(resources,
        WebUtils.safeEncode(websafeResult.websafeRefetchCursor),
        WebUtils.safeEncode(websafeResult.websafeBackwardCursor),
        WebUtils.safeEncode(websafeResult.websafeResumeCursor), websafeResult.hasMore,
        websafeResult.hasPrior);

    // set the app-level manifest ETag if known...
    try {
      tableResourceList
          .setAppLevelManifestETag(FileManifestService.getAppLevelManifestETag(callingContext));
    } catch (ODKDatastoreException e) {
      // ignore
    }

    return Response.ok(tableResourceList)
        .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
        .header("Access-Control-Allow-Origin", "*")
        .header("Access-Control-Allow-Credentials", "true").build();
  }

  /**
   * Get a particular tableId (supplied in implementation constructor)
   *
   * @return {@link TableResource} of the requested table.
   * @throws ODKDatastoreException
   * @throws PermissionDeniedException
   * @throws ODKTaskLockException
   * @throws TableNotFoundException
   */
  @GET
  @Produces({MediaType.APPLICATION_JSON, ApiConstants.MEDIA_TEXT_XML_UTF8, ApiConstants.MEDIA_APPLICATION_XML_UTF8})
  public Response getTable() throws ODKDatastoreException, TableNotFoundException,
      PermissionDeniedException, ODKTaskLockException {

    TablesUserPermissions userPermissions = new TablesUserPermissionsImpl(callingContext);

    TableManager tm = new TableManager(appId, userPermissions, callingContext);
    TableEntry entry = tm.getTable(tableId);
    if (entry == null || entry.getSchemaETag() == null) {
      // the table doesn't exist yet (or something is there that is database
      // cruft)
      throw new TableNotFoundException(ERROR_TABLE_NOT_FOUND + "\n" + tableId);
    }
    TableResource resource = getResource(info, appId, entry);

    // set the table-level manifest ETag if known...
    try {
      resource.setTableLevelManifestETag(FileManifestService.getTableLevelManifestETag(
          entry.getTableId(), callingContext));
    } catch (ODKDatastoreException e) {
      // ignore
    }

    return Response.ok(resource)
        .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
        .header("Access-Control-Allow-Origin", "*")
        .header("Access-Control-Allow-Credentials", "true").build();
  }

  /**
   * Create a particular tableId (supplied in implementation constructor)
   *
   * @param definition
   * @return {@link TableResource} of the table. This may already exist (with identical schema) or be newly created.
   * @throws ODKDatastoreException
   * @throws TableAlreadyExistsException
   * @throws PermissionDeniedException
   * @throws ODKTaskLockException
   * @throws IOException
   */
  @PUT
  @Consumes({MediaType.APPLICATION_JSON, ApiConstants.MEDIA_TEXT_XML_UTF8, ApiConstants.MEDIA_APPLICATION_XML_UTF8})
  @Produces({MediaType.APPLICATION_JSON, ApiConstants.MEDIA_TEXT_XML_UTF8, ApiConstants.MEDIA_APPLICATION_XML_UTF8})

  public Response createTable(TableDefinition definition) throws ODKDatastoreException,
      TableAlreadyExistsException, PermissionDeniedException, ODKTaskLockException, IOException {

    TreeSet<GrantedAuthorityName> ui = SecurityServiceUtil.getCurrentUserSecurityInfo(callingContext);
    if (!ui.contains(GrantedAuthorityName.ROLE_ADMINISTER_TABLES)) {
      throw new PermissionDeniedException("User does not belong to the 'Administer Tables' group");
    }

    TablesUserPermissions userPermissions = new TablesUserPermissionsImpl(callingContext);

    TableManager tm = new TableManager(appId, userPermissions, callingContext);
    // NOTE: the only access control restriction for
    // creating the table is the Administer Tables role.
    List<Column> columns = definition.getColumns();

    TableEntry entry = tm.createTable(tableId, columns, null);
    TableResource resource = getResource(info, appId, entry);

    // set the table-level manifest ETag if known...
    try {
      resource.setTableLevelManifestETag(FileManifestService.getTableLevelManifestETag(
          entry.getTableId(), callingContext));
    } catch (ODKDatastoreException e) {
      // ignore
    }

    logger.info(String.format("createTable: tableId: %s, definition: %s", tableId, definition));

    return Response.ok(resource)
        .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
        .header("Access-Control-Allow-Origin", "*")
        .header("Access-Control-Allow-Credentials", "true").build();
  }

  /**
   * Get the realized version of this table.
   *
   * @param schemaETag
   * @return
   * @throws ODKDatastoreException
   * @throws PermissionDeniedException
   * @throws SchemaETagMismatchException
   * @throws AppNameMismatchException
   * @throws ODKTaskLockException
   * @throws TableNotFoundException
   */
  @Path("ref/{schemaETag}")
  public RealizedTableService getRealizedTable(@PathParam("schemaETag") String schemaETag)
      throws ODKDatastoreException, PermissionDeniedException, SchemaETagMismatchException,
      AppNameMismatchException, ODKTaskLockException, TableNotFoundException {

    TablesUserPermissions userPermissions = new TablesUserPermissionsImpl(callingContext);

    TableManager tm = new TableManager(appId, userPermissions, callingContext);
    TableEntry entry = tm.getTable(tableId);
    if (entry == null) {
      // the table doesn't exist yet (or something is there that is database
      // cruft)
      throw new TableNotFoundException(ERROR_TABLE_NOT_FOUND + "\n" + tableId);
    }
    if (entry.getSchemaETag() != null && !entry.getSchemaETag().equals(schemaETag)) {
      throw new SchemaETagMismatchException(ERROR_SCHEMA_DIFFERS + "\n" + entry.getSchemaETag());
    }
    RealizedTableService service = new RealizedTableService(sc, req, headers, info, appId,
        tableId, schemaETag, (entry.getSchemaETag() == null), userPermissions, tm, callingContext);
    return service;

  }

  /**
   * ACL manager for a particular tableId (supplied in implementation constructor)
   *
   * @return {@link TableAclService} for ACL management on this table.
   * @throws ODKDatastoreException
   * @throws AppNameMismatchException
   * @throws ODKTaskLockException
   * @throws PermissionDeniedException
   */
  @Path("acl")
  public TableAclService getAcl() throws ODKDatastoreException, AppNameMismatchException,
      PermissionDeniedException, ODKTaskLockException {

    TablesUserPermissions userPermissions = new TablesUserPermissionsImpl(callingContext);

    // orthogonal to access rights to the table...
    // TableManager tm = new TableManager(appId, userPermissions, cc);
    TableAclService service = new TableAclService(appId, tableId, info, userPermissions, callingContext);
    return service;
  }

  private TableResource getResource(UriInfo info, String appId, TableEntry entry) {
    String tableId = entry.getTableId();
    String schemaETag = entry.getSchemaETag();

    UriBuilder uriBuilder = info.getBaseUriBuilder();
    uriBuilder.path(OdkTables.class);
    uriBuilder.path(OdkTables.class, "getTablesService");
    URI self = uriBuilder.clone().build(appId, tableId);
    UriBuilder realized = uriBuilder.clone().path(TableService.class, "getRealizedTable");
    URI data = realized.clone().path(RealizedTableService.class, "getData")
        .build(appId, tableId, schemaETag);
    URI instanceFiles = realized.clone().path(RealizedTableService.class, "getInstanceFileService")
        .build(appId, tableId, schemaETag);
    URI diff = realized.clone().path(RealizedTableService.class, "getDiff")
        .build(appId, tableId, schemaETag);
    URI acl = uriBuilder.clone().path(TableService.class, "getAcl").build(appId, tableId);
    URI definition = realized.clone().build(appId, tableId, schemaETag);

    TableResource resource = new TableResource(entry);
    try {
      resource.setSelfUri(self.toURL().toExternalForm());
      resource.setDefinitionUri(definition.toURL().toExternalForm());
      resource.setDataUri(data.toURL().toExternalForm());
      resource.setInstanceFilesUri(instanceFiles.toURL().toExternalForm());
      resource.setDiffUri(diff.toURL().toExternalForm());
      resource.setAclUri(acl.toURL().toExternalForm());
    } catch (MalformedURLException e) {
      e.printStackTrace();
    }
    return resource;
  }

  /**
   * Get the properties.csv for this tableId.
   * 
   * The properties.csv is not versioned but is atomically 
   * updated. It is the metadata for the tableId excluding
   * the data type definitions which are defined in the 
   * TableDefinition's Column array.
   * 
   * @param odkClientVersion
   * @return
   * @throws ODKDatastoreException
   * @throws PermissionDeniedException
   * @throws ODKTaskLockException
   * @throws TableNotFoundException
   * @throws FileNotFoundException 
   */
  @GET
  @Path("properties/{odkClientVersion}")
  @Produces({MediaType.APPLICATION_JSON, ApiConstants.MEDIA_TEXT_XML_UTF8, ApiConstants.MEDIA_APPLICATION_XML_UTF8})
  public Response getTableProperties(@PathParam("odkClientVersion") String odkClientVersion) 
      throws ODKDatastoreException, PermissionDeniedException,
      ODKTaskLockException, TableNotFoundException, FileNotFoundException {

    TablesUserPermissions userPermissions = new TablesUserPermissionsImpl(callingContext);

    String appRelativePath = FileManager.getPropertiesFilePath(tableId);

    FileContentInfo fi;

    userPermissions.checkPermission(appId, tableId, TablePermission.READ_PROPERTIES);

    FileManager fm = new FileManager(appId, callingContext);

    fi = fm.getFile(odkClientVersion, tableId, appRelativePath);

    // And now prepare everything to be returned to the caller.
    if (fi.fileBlob != null && fi.contentType != null && fi.contentLength != null
        && fi.contentLength != 0L) {
      // read the byte[] array using the CSV reader, and build a
      // list of PropertyEntry objects.
      ByteArrayInputStream bas = new ByteArrayInputStream(fi.fileBlob);
      Reader rdr = null;
      RFC4180CsvReader csvReader = null;
      ArrayList<PropertyEntryXml> properties = new ArrayList<PropertyEntryXml>();
      try {
        rdr = new InputStreamReader(bas, CharEncoding.UTF_8);
        csvReader = new RFC4180CsvReader(rdr);

        String[] entries = csvReader.readNext();
        if (entries.length != 5) {
          throw new IllegalStateException("Uploaded properties.csv does not have 5 columns!");
        }

        if (!"_partition".equals(entries[0])) {
          throw new IllegalStateException(
              "Uploaded properties.csv does not have 'partition' as first column heading!");
        }

        if (!"_aspect".equals(entries[1])) {
          throw new IllegalStateException(
              "Uploaded properties.csv does not have 'aspect' as second column heading!");
        }

        if (!"_key".equals(entries[2])) {
          throw new IllegalStateException(
              "Uploaded properties.csv does not have 'key' as third column heading!");
        }

        if (!"_type".equals(entries[3])) {
          throw new IllegalStateException(
              "Uploaded properties.csv does not have 'type' as fourth column heading!");
        }

        if (!"_value".equals(entries[4])) {
          throw new IllegalStateException(
              "Uploaded properties.csv does not have 'value' as fifth column heading!");
        }

        entries = csvReader.readNext();
        while (entries != null) {
          PropertyEntryXml e = new PropertyEntryXml(entries[0], entries[1], entries[2], entries[3],
              entries[4]);
          properties.add(e);
          entries = csvReader.readNext();
        }
      } catch (UnsupportedEncodingException ex) {
        ex.printStackTrace();
        throw new IllegalStateException("unrecognized UTF-8 charset encoding!");
      } catch (IOException ex) {
        ex.printStackTrace();
        throw new IllegalStateException("unable to parse properties.csv!");
      } finally {
        if (csvReader != null) {
          try {
            csvReader.close();
          } catch (IOException e) {
            e.printStackTrace();
          }
        } else if (rdr != null) {
          try {
            rdr.close();
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      }

      PropertyEntryXmlList pl = new PropertyEntryXmlList(properties);

      List<MediaType> acceptableMedia = headers.getAcceptableMediaTypes();
      double maxJson = 0.0;
      double maxOther = 0.0;
      MediaType xmlType = null;
      for (MediaType m : acceptableMedia) {
        // get q value, if any (default = 1.0).
        double weight = 0.0;
        String quotient = m.getParameters().get("q");
        if (quotient != null) {
          try {
            weight = Double.valueOf(quotient);
          } catch (NumberFormatException e) {
            weight = 1.0;
          }
        } else {
          weight = 1.0;
        }

        if (m.isCompatible(MediaType.APPLICATION_JSON_TYPE)) {
          // this will snarf "*/*", so we will prefer the JSON return format.
          maxJson = (maxJson > weight) ? maxJson : weight;
        } else if (m.isCompatible(MediaType.valueOf(ApiConstants.MEDIA_TEXT_XML_UTF8))
            || m.isCompatible(MediaType.valueOf(ApiConstants.MEDIA_APPLICATION_XML_UTF8))) {
          if (weight > maxOther) {
            maxOther = weight;
            xmlType = m;
          }
        }
      }

      if (maxJson >= maxOther) {
        // re-write as full Json object...
        PropertyEntryJsonList pjson = new PropertyEntryJsonList();
        for (PropertyEntryXml e : properties) {
          PropertyEntryJson tpe = new PropertyEntryJson(e.getPartition(), e.getAspect(),
              e.getKey(), e.getType(), null);
          String value = e.getValue();
          if (value == null) {
            // shouldn't happen...
            tpe.setValue(null);
            continue;
          }
          String type = e.getType();
          if (type.equals("string")) {
            tpe.setValue(value);
          } else if (type.equals("number")) {
            try {
              double d = Double.valueOf(value);
              tpe.setValue(d);
            } catch (NumberFormatException ex) {
              // swallow...
              tpe.setValue(null);
            }
          } else if (type.equals("integer")) {
            try {
              int i = Integer.valueOf(value);
              tpe.setValue(i);
            } catch (NumberFormatException ex) {
              // swallow...
              tpe.setValue(null);
            }
          } else if (type.equals("boolean")) {
            boolean b = Boolean.valueOf(value);
            tpe.setValue(b);
          } else {
            // could be anything. most likely
            // an array or object -- just convert
            // and store...
            Object o = null;
            try {
              o = mapper.readValue(value, Object.class);
            } catch (JsonParseException ex) {
              ex.printStackTrace();
            } catch (JsonMappingException ex) {
              ex.printStackTrace();
            } catch (IOException ex) {
              ex.printStackTrace();
            }
            tpe.setValue(o);
          }
          pjson.add(tpe);
        }

        ResponseBuilder rBuild = Response.ok(pjson, MediaType.APPLICATION_JSON_TYPE)
            .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Allow-Credentials", "true");
        return rBuild.build();
      } else {
        ResponseBuilder rBuild = Response.ok(pl, xmlType)
            .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Allow-Credentials", "true");
        return rBuild.build();
      }
    } else {
      PropertyEntryXmlList pl = new PropertyEntryXmlList(null);

      ResponseBuilder rBuild = Response.ok(pl)
          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
          .header("Access-Control-Allow-Origin", "*")
          .header("Access-Control-Allow-Credentials", "true");
      return rBuild.build();
    }
  }

  /**
   * Replace the properties.csv with the supplied propertiesList.
   * This does not preserve the existing properties in the properties.csv,
   * but does a wholesale, atomic, replacement of those properties.
   * 
   * This is the XML variant of this API. See putJsonTableProperties, below.
   * 
   * @param odkClientVersion
   * @param propertiesList
   * @return
   * @throws ODKDatastoreException
   * @throws PermissionDeniedException
   * @throws ODKTaskLockException
   * @throws TableNotFoundException
   */
  @PUT
  @Path("properties/{odkClientVersion}")
  @Consumes({ApiConstants.MEDIA_TEXT_XML_UTF8, ApiConstants.MEDIA_APPLICATION_XML_UTF8})
  @Produces({MediaType.APPLICATION_JSON, ApiConstants.MEDIA_TEXT_XML_UTF8, ApiConstants.MEDIA_APPLICATION_XML_UTF8})
  public Response putXmlTableProperties(@PathParam("odkClientVersion") String odkClientVersion, PropertyEntryXmlList propertiesList)
      throws ODKDatastoreException, PermissionDeniedException, ODKTaskLockException,
      TableNotFoundException {
    return putInternalTableProperties(odkClientVersion, propertiesList);
  }

  /**
   * Replace the properties.csv with the supplied propertiesList.
   * This does not preserve the existing properties in the properties.csv,
   * but does a wholesale, atomic, replacement of those properties.
   * 
   * This is the JSON variant of this API. See putXmlTableProperties, above.
   * 
   * @param odkClientVersion
   * @param propertiesList
   * @return
   * @throws ODKDatastoreException
   * @throws PermissionDeniedException
   * @throws ODKTaskLockException
   * @throws TableNotFoundException
   */
  @PUT
  @Path("properties/{odkClientVersion}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON, ApiConstants.MEDIA_TEXT_XML_UTF8, ApiConstants.MEDIA_APPLICATION_XML_UTF8})
  public Response putJsonTableProperties(@PathParam("odkClientVersion") String odkClientVersion, ArrayList<Map<String, Object>> propertiesList)
      throws ODKDatastoreException, PermissionDeniedException, ODKTaskLockException,
      TableNotFoundException {
    ArrayList<PropertyEntryXml> properties = new ArrayList<PropertyEntryXml>();
    for (Map<String, Object> tpe : propertiesList) {
      // bogus type and value...
      String partition = (String) tpe.get("partition");
      String aspect = (String) tpe.get("aspect");
      String key = (String) tpe.get("key");
      String type = (String) tpe.get("type");
      PropertyEntryXml e = new PropertyEntryXml(partition, aspect, key, type, null);

      // and figure out the correct type and value...
      Object value = tpe.get("value");
      if (value == null) {
        e.setValue(null);
      } else if (value instanceof Boolean) {
        e.setValue(Boolean.toString((Boolean) value));
      } else if (value instanceof Integer) {
        e.setValue(Integer.toString((Integer) value));
      } else if (value instanceof Float) {
        e.setValue(Float.toString((Float) value));
      } else if (value instanceof Double) {
        e.setValue(Double.toString((Double) value));
      } else if (value instanceof List) {
        try {
          e.setValue(mapper.writeValueAsString(value));
        } catch (JsonProcessingException ex) {
          ex.printStackTrace();
          e.setValue("[]");
        }
      } else {
        try {
          e.setValue(mapper.writeValueAsString(value));
        } catch (JsonProcessingException ex) {
          ex.printStackTrace();
          e.setValue("{}");
        }
      }

      properties.add(e);
    }
    PropertyEntryXmlList pl = new PropertyEntryXmlList(properties);
    return putInternalTableProperties(odkClientVersion, pl);
  }

  public Response putInternalTableProperties(String odkClientVersion, PropertyEntryXmlList propertiesList)
      throws ODKDatastoreException, PermissionDeniedException, ODKTaskLockException,
      TableNotFoundException {

    TreeSet<GrantedAuthorityName> ui = SecurityServiceUtil.getCurrentUserSecurityInfo(callingContext);
    if (!ui.contains(GrantedAuthorityName.ROLE_ADMINISTER_TABLES)) {
      throw new PermissionDeniedException("User does not belong to the 'Administer Tables' group");
    }

    TablesUserPermissions userPermissions = new TablesUserPermissionsImpl(callingContext);

    String appRelativePath = FileManager.getPropertiesFilePath(tableId);

    String contentType = ServletConsts.CONTENT_TYPE_CSV_UTF8;

    // DbTableFileInfo.NO_TABLE_ID -- means that we are working with app-level
    // permissions
    userPermissions.checkPermission(appId, tableId, TablePermission.WRITE_PROPERTIES);

    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    Writer wtr = null;
    RFC4180CsvWriter csvWtr = null;

    try {
      wtr = new OutputStreamWriter(bas, CharEncoding.UTF_8);
      csvWtr = new RFC4180CsvWriter(wtr);
      String[] entry = new String[5];
      entry[0] = "_partition";
      entry[1] = "_aspect";
      entry[2] = "_key";
      entry[3] = "_type";
      entry[4] = "_value";
      csvWtr.writeNext(entry);
      for (PropertyEntryXml e : propertiesList.getProperties()) {
        entry[0] = e.getPartition();
        entry[1] = e.getAspect();
        entry[2] = e.getKey();
        entry[3] = e.getType();
        entry[4] = e.getValue();
        csvWtr.writeNext(entry);
      }
      csvWtr.flush();
    } catch (UnsupportedEncodingException ex) {
      ex.printStackTrace();
      throw new IllegalStateException("Unrecognized UTF-8 charset!");
    } catch (IOException ex) {
      ex.printStackTrace();
      throw new IllegalStateException("Unable to write into a byte array!");
    } finally {
      if (csvWtr != null) {
        try {
          csvWtr.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      } else if (wtr != null) {
        try {
          wtr.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    byte[] content = bas.toByteArray();

    FileManager fm = new FileManager(appId, callingContext);

    FileContentInfo fi = new FileContentInfo(appRelativePath, contentType, Long.valueOf(content.length), null,
        content);

    @SuppressWarnings("unused")
    ConfigFileChangeDetail outcome = fm.putFile(odkClientVersion, tableId, fi, userPermissions);
    return Response.status(Status.ACCEPTED)
        .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
        .header("Access-Control-Allow-Origin", "*")
        .header("Access-Control-Allow-Credentials", "true").build();
  }

}