package com.twitter.ambrose.server;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.sun.org.apache.xml.internal.security.exceptions.Base64DecodingException;
import com.sun.org.apache.xml.internal.security.utils.Base64;

import org.mortbay.jetty.HttpConnection;
import org.mortbay.jetty.Request;
import org.mortbay.jetty.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.twitter.ambrose.model.DAGNode;
import com.twitter.ambrose.model.Event;
import com.twitter.ambrose.model.Job;
import com.twitter.ambrose.model.PaginatedList;
import com.twitter.ambrose.model.WorkflowSummary;
import com.twitter.ambrose.model.WorkflowSummary.Status;
import com.twitter.ambrose.service.StatsReadService;
import com.twitter.ambrose.service.WorkflowIndexReadService;
import com.twitter.ambrose.util.JSONUtil;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Handler for the API data responses.
 *
 * @author billg
 */
public class APIHandler extends AbstractHandler {
  private static void sendJson(HttpServletRequest request,
      HttpServletResponse response, Object object) throws IOException {
    JSONUtil.writeJson(response.getWriter(), object);
    response.getWriter().close();
    setHandled(request);
  }

  private static void setHandled(HttpServletRequest request) {
    Request base_request = (request instanceof Request) ?
        (Request) request : HttpConnection.getCurrentConnection().getRequest();
    base_request.setHandled(true);
  }

  private static final Logger LOG = LoggerFactory.getLogger(APIHandler.class);
  private static final String QUERY_PARAM_CLUSTER = "cluster";
  private static final String QUERY_PARAM_USER = "user";
  private static final String QUERY_PARAM_STATUS = "status";
  private static final String QUERY_PARAM_START_KEY = "startKey";
  private static final String QUERY_PARAM_WORKFLOW_ID = "workflowId";
  private static final String QUERY_PARAM_LAST_EVENT_ID = "lastEventId";
  private static final String MIME_TYPE_HTML = "text/html";
  private static final String MIME_TYPE_JSON = "application/json";
  private WorkflowIndexReadService workflowIndexReadService;
  private StatsReadService<Job> statsReadService;

  public APIHandler(WorkflowIndexReadService workflowIndexReadService,
      StatsReadService<Job> statsReadService) {
    this.workflowIndexReadService = workflowIndexReadService;
    this.statsReadService = statsReadService;
  }

  @Override
  public void handle(String target,
      HttpServletRequest request,
      HttpServletResponse response,
      int dispatch) throws IOException, ServletException {

    if (target.endsWith("/workflows")) {
      String cluster = checkNotNull(request.getParameter(QUERY_PARAM_CLUSTER)).trim();
      String user = request.getParameter(QUERY_PARAM_USER);

      if (user != null) {
        user = user.trim();
        if (user.isEmpty()) {
          user = null;
        }
      }

      Status status = Status.valueOf(checkNotNull(request.getParameter(QUERY_PARAM_STATUS).trim()));
      String startRowParam = request.getParameter(QUERY_PARAM_START_KEY);
      byte[] startRow = null;
      if (startRowParam != null && !startRowParam.isEmpty()) {
        try {
          startRow = Base64.decode(startRowParam);
        } catch (Base64DecodingException e) {
          throw new IOException(String.format(
              "Failed to decode '%s' parameter value '%s'",
              QUERY_PARAM_START_KEY, startRowParam), e);
        }
      }

      LOG.info("Submitted request for cluster={}, user={}, status={}, startRow={}", cluster, user,
          status, startRowParam);
      PaginatedList<WorkflowSummary> workflows = workflowIndexReadService.getWorkflows(
          cluster, status, user, 10, startRow);

      response.setContentType(MIME_TYPE_JSON);
      response.setStatus(HttpServletResponse.SC_OK);
      sendJson(request, response, workflows);

    } else if (target.endsWith("/dag")) {
      String workflowId = checkNotNull(request.getParameter(QUERY_PARAM_WORKFLOW_ID));

      LOG.info("Submitted request for workflowId={}", workflowId);
      Map<String, DAGNode<Job>> dagNodeNameMap =
          statsReadService.getDagNodeNameMap(workflowId);
      Collection<DAGNode<Job>> nodes = dagNodeNameMap.values();

      response.setContentType(MIME_TYPE_JSON);
      response.setStatus(HttpServletResponse.SC_OK);
      sendJson(request, response, nodes.toArray(new DAGNode[nodes.size()]));

    } else if (target.endsWith("/events")) {
      Integer lastEventId = request.getParameter(QUERY_PARAM_LAST_EVENT_ID) != null ?
          Integer.parseInt(request.getParameter(QUERY_PARAM_LAST_EVENT_ID)) : -1;

      LOG.info("Submitted request for lastEventId={}", lastEventId);
      Collection<Event> events = statsReadService
          .getEventsSinceId(request.getParameter(QUERY_PARAM_WORKFLOW_ID), lastEventId);

      response.setContentType(MIME_TYPE_JSON);
      response.setStatus(HttpServletResponse.SC_OK);
      sendJson(request, response, events.toArray(new Event[events.size()]));

    } else if (target.endsWith(".html")) {
      response.setContentType(MIME_TYPE_HTML);
      // this is because the next handler will be picked up here and it doesn't seem to
      // handle html well. This is jank.
    }
  }
}
