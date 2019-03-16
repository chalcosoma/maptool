/*
 * This software Copyright by the RPTools.net development team, and
 * licensed under the Affero GPL Version 3 or, at your option, any later
 * version.
 *
 * MapTool Source Code is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public
 * License * along with this source Code.  If not, please visit
 * <http://www.gnu.org/licenses/> and specifically the Affero license
 * text at <http://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.maptool.client.functions;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.awt.Desktop;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.rptools.maptool.client.AppPreferences;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.language.I18N;
import net.rptools.parser.Parser;
import net.rptools.parser.ParserException;
import net.rptools.parser.function.AbstractFunction;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * RESTful based functions REST.get, REST.post, REST.put, REST.patch, REST.delete
 *
 * <p>Functions take 1 to 5 parameters depending on function called. A minimum of a URL is always
 * required.
 *
 * <p>A body (payload) & Media Type are always required for post, put, & patch and optional for
 * delete.
 *
 * <p>Headers are optional on all functions passed in as a JSON as a String: String[] array of
 * Strings
 *
 * <p>A boolean at the end is always option to request a full response. A BigDecimal of 1 will
 * return HTTP status code, headers, message (if present), and body of response. Otherwise just the
 * body of the response is returned in what ever form the call returns.
 */
public class RESTfulFunctions extends AbstractFunction {
  private static final Logger log = LogManager.getLogger();

  private static final RESTfulFunctions instance = new RESTfulFunctions();

  private RESTfulFunctions() {
    super(1, 5, "REST.get", "REST.post", "REST.put", "REST.patch", "REST.delete");
  }

  public static RESTfulFunctions getInstance() {
    return instance;
  }

  private final OkHttpClient client = new OkHttpClient();
  private final Gson gson = new Gson();

  @Override
  public Object childEvaluate(Parser parser, String functionName, List<Object> parameters)
      throws ParserException {

    if (!MapTool.getParser().isMacroPathTrusted())
      throw new ParserException(I18N.getText("macro.function.general.noPerm", functionName));

    if (!AppPreferences.getAllowExternalMacroAccess())
      throw new ParserException(I18N.getText("macro.function.general.accessDenied", functionName));

    // Check do we need this?
    checkParameters(functionName, parameters, 1, 5);

    if (functionName.equalsIgnoreCase("REST.get")) return buildGetRequest(functionName, parameters);

    if (functionName.equalsIgnoreCase("REST.delete"))
      return buildDeleteRequest(functionName, parameters);

    if (functionName.equalsIgnoreCase("REST.post")
        || functionName.equalsIgnoreCase("REST.put")
        || functionName.equalsIgnoreCase("REST.patch"))
      return buildRequest(functionName, parameters);
    else
      throw new ParserException(
          I18N.getText("macro.function.general.unknownFunction", functionName));
  }

  /**
   * Performs a RESTful GET request using OkHttp
   *
   * @param functionName
   * @param parameters include URL, headers (optional) and if full response is requested (optional)
   * @return HTTP response as JSON (if full response) or server response, usually JSON but can be
   *     XML, HTML, or other formats.
   * @throws ParserException
   */
  private Object buildGetRequest(String functionName, List<Object> parameters)
      throws ParserException {
    checkParameters(functionName, parameters, 1, 3);

    Request request;
    String baseURL = parameters.get(0).toString();
    Map<String, List<String>> headerMap = new HashMap<String, List<String>>();
    boolean fullResponse = false;

    // Special case, syrinscape URI use java.awt.Desktop to launch URI
    if (baseURL.startsWith("syrinscape")) {
      checkParameters(functionName, parameters, 1, 1);
      return launchSyrinscape(baseURL);
    }

    if (parameters.size() == 2) {
      if (parameters.get(1) instanceof BigDecimal)
        fullResponse = AbstractTokenAccessorFunction.getBooleanValue(parameters.get(1));
      else
        headerMap =
            gson.fromJson(
                (String) parameters.get(1),
                new TypeToken<Map<String, List<String>>>() {}.getType());
    }

    if (parameters.size() == 3) {
      headerMap =
          gson.fromJson(
              (String) parameters.get(1), new TypeToken<Map<String, List<String>>>() {}.getType());
      fullResponse = AbstractTokenAccessorFunction.getBooleanValue(parameters.get(2));
    }

    // Build the GET request
    request = new Request.Builder().url(baseURL).build();

    // If we need to add headers then rebuild a new request with new headers
    if (!headerMap.isEmpty()) {
      Headers.Builder headersBuilder = request.headers().newBuilder();

      for (Map.Entry<String, List<String>> entry : headerMap.entrySet()) {
        String name = entry.getKey();
        List<String> values = entry.getValue();
        for (String value : values) headersBuilder.add(name, value);
      }

      request = new Request.Builder().url(baseURL).headers(headersBuilder.build()).build();
    }

    return executeClientCall(functionName, request, fullResponse);
  }

  /**
   * Performs a RESTful POST, PATCH, or PUT request using OkHttp Minimum requirements are URL,
   * Payload, & MediaType Optional parameters are Headers & Full Response
   *
   * @param functionName
   * @param parameters include URL, payload, media type of payload, headers (optional) and if full
   *     response is requested (optional)
   * @return HTTP response as JSON (if full response) or server response, usually JSON but can be
   *     XML, HTML, or other formats.
   * @throws ParserException
   */
  private Object buildRequest(String functionName, List<Object> parameters) throws ParserException {
    checkParameters(functionName, parameters, 3, 5);

    Request request;
    Map<String, List<String>> headerMap = new HashMap<String, List<String>>();
    boolean fullResponse = false;

    String baseURL = parameters.get(0).toString();
    String payload = parameters.get(1).toString();
    MediaType mediaType = MediaType.parse(parameters.get(2).toString());

    if (parameters.size() == 4) {
      if (parameters.get(3) instanceof BigDecimal)
        fullResponse = AbstractTokenAccessorFunction.getBooleanValue(parameters.get(3));
      else
        headerMap =
            gson.fromJson(
                (String) parameters.get(3),
                new TypeToken<Map<String, List<String>>>() {}.getType());
    }

    if (parameters.size() == 5) {
      headerMap =
          gson.fromJson(
              (String) parameters.get(3), new TypeToken<Map<String, List<String>>>() {}.getType());
      fullResponse = AbstractTokenAccessorFunction.getBooleanValue(parameters.get(4));
    }

    // Build out the request body
    RequestBody requestBody = RequestBody.create(mediaType, payload);

    // If we need to add headers then build a new request with new headers

    if (headerMap.isEmpty()) {
      // Build without any headers...
      if (functionName.equals("REST.post"))
        request = new Request.Builder().url(baseURL).post(requestBody).build();
      else if (functionName.equals("REST.put"))
        request = new Request.Builder().url(baseURL).put(requestBody).build();
      else if (functionName.equals("REST.patch"))
        request = new Request.Builder().url(baseURL).patch(requestBody).build();
      else
        throw new ParserException(
            I18N.getText("macro.function.general.unknownFunction", functionName));
    } else {
      // We need a request to create headers, seems stupid, could find another way...
      request = new Request.Builder().url(baseURL).build();
      Headers.Builder headersBuilder = request.headers().newBuilder();

      for (Map.Entry<String, List<String>> entry : headerMap.entrySet()) {
        String name = entry.getKey();
        List<String> values = entry.getValue();
        for (String value : values) headersBuilder.add(name, value);
      }

      Headers headers = headersBuilder.build();

      // Now build request with headers
      if (functionName.equals("REST.post"))
        request = new Request.Builder().url(baseURL).post(requestBody).headers(headers).build();
      else if (functionName.equals("REST.put"))
        request = new Request.Builder().url(baseURL).put(requestBody).headers(headers).build();
      else if (functionName.equals("REST.patch"))
        request = new Request.Builder().url(baseURL).patch(requestBody).headers(headers).build();
    }

    return executeClientCall(functionName, request, fullResponse);
  }

  /**
   * Performs a DELETE request using OkHttp Minimum requirements is a URL. Optional parameters are
   * Headers & Full Response
   *
   * @param functionName
   * @param parameters include URL, headers (optional) and if full response is requested (optional)
   * @return HTTP response as JSON (if full response) or server response, usually JSON but can be
   *     XML, HTML, or other formats.
   * @throws ParserException
   */
  private Object buildDeleteRequest(String functionName, List<Object> parameters)
      throws ParserException {
    checkParameters(functionName, parameters, 1, 3);

    Request request;
    String baseURL = parameters.get(0).toString();
    Map<String, List<String>> headerMap = new HashMap<String, List<String>>();
    boolean fullResponse = false;

    if (parameters.size() == 2) {
      if (parameters.get(1) instanceof BigDecimal)
        fullResponse = AbstractTokenAccessorFunction.getBooleanValue(parameters.get(1));
      else
        headerMap =
            gson.fromJson(
                (String) parameters.get(1),
                new TypeToken<Map<String, List<String>>>() {}.getType());
    }

    if (parameters.size() == 3) {
      headerMap =
          gson.fromJson(
              (String) parameters.get(1), new TypeToken<Map<String, List<String>>>() {}.getType());
      fullResponse = AbstractTokenAccessorFunction.getBooleanValue(parameters.get(2));
    }

    // Build the Delete request
    request = new Request.Builder().delete().url(baseURL).build();

    // If we need to add headers then build a new request with new headers
    if (!headerMap.isEmpty()) {
      Headers.Builder headersBuilder = request.headers().newBuilder();

      for (Map.Entry<String, List<String>> entry : headerMap.entrySet()) {
        String name = entry.getKey();
        List<String> values = entry.getValue();
        for (String value : values) headersBuilder.add(name, value);
      }

      request = new Request.Builder().delete().url(baseURL).headers(headersBuilder.build()).build();
    }

    Object response = executeClientCall(functionName, request, fullResponse);

    // For deletes, if a full response is not requested then an empty response is successful
    if (response.toString().isEmpty()) response = BigDecimal.ONE;

    return response;
  }

  /**
   * Execute RESTful Client Call for the request and return appropriate response body
   *
   * @param functionName
   * @param request
   * @param fullResponse
   * @return
   * @throws ParserException
   */
  private Object executeClientCall(String functionName, Request request, boolean fullResponse)
      throws ParserException {

    // Execute the call and check the response...
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() && !fullResponse)
        throw new ParserException(
            I18N.getText("macro.function.rest.error.response", functionName, response.code()));

      if (fullResponse) return gson.toJson(new RestResponseObj(response));
      else return response.body().string();

    } catch (IllegalArgumentException | IOException e) {
      throw new ParserException(I18N.getText("macro.function.rest.error.unknown", functionName, e));
    }
  }

  /*
   * @param parameters include URI
   * @return BigDecimal.ONE if successful, BigDecimal.ZERO if Syrinscape is not activated in preferences
   * @throws ParserException
   */
  private BigDecimal launchSyrinscape(String baseURL) throws ParserException {
    if (!AppPreferences.getSyrinscapeActive()) return BigDecimal.ZERO;

    URI uri;

    if (Desktop.isDesktopSupported()) {
      try {
        uri = new URI(baseURL);
        Desktop.getDesktop().browse(uri);
      } catch (IOException | URISyntaxException e) {
        throw new ParserException(I18N.getText("macro.function.rest.syrinscape.error"));
      }
    }

    return BigDecimal.ONE;
  }

  /**
   * @param function's name
   * @param parameters passed into the function call
   * @param min number of parameters required
   * @param max number of parameters required
   * @throws ParserException
   */
  private void checkParameters(String functionName, List<Object> parameters, int min, int max)
      throws ParserException {

    if (min == max) {
      if (parameters.size() != max)
        throw new ParserException(
            I18N.getText(
                "macro.function.general.wrongNumParam", functionName, max, parameters.size()));

    } else {
      if (parameters.size() < min)
        throw new ParserException(
            I18N.getText(
                "macro.function.general.notEnoughParam", functionName, min, parameters.size()));

      if (parameters.size() > max)
        throw new ParserException(
            I18N.getText(
                "macro.function.general.tooManyParam", functionName, max, parameters.size()));
    }
  }

  /*
   * A POJO to hold an HTTP Response object to marshal as a JSON object
   */
  private final class RestResponseObj {
    private final int status;
    private final String message;
    private final Map<String, List<String>> headers;
    private final JsonElement body;

    public RestResponseObj(Response response) throws IOException {
      this.status = response.code();
      this.headers = response.headers().toMultimap();

      if (!response.message().isEmpty()) this.message = response.message();
      else this.message = null;

      String responseBody = response.body().string();
      if (isValidJSON(responseBody)) body = gson.fromJson(responseBody, JsonElement.class);
      else this.body = new Gson().toJsonTree(responseBody);
    }

    private boolean isValidJSON(String jsonInString) {
      try {
        gson.fromJson(jsonInString, JsonElement.class);
        return true;
      } catch (com.google.gson.JsonSyntaxException ex) {
        return false;
      }
    }
  }
}
