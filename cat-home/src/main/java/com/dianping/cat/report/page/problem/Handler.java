package com.dianping.cat.report.page.problem;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;

import com.dianping.cat.configuration.ServerConfigManager;
import com.dianping.cat.configuration.server.entity.Domain;
import com.dianping.cat.consumer.problem.model.entity.Machine;
import com.dianping.cat.consumer.problem.model.entity.ProblemReport;
import com.dianping.cat.consumer.problem.model.transform.DefaultDomParser;
import com.dianping.cat.hadoop.dal.Dailyreport;
import com.dianping.cat.hadoop.dal.DailyreportDao;
import com.dianping.cat.hadoop.dal.DailyreportEntity;
import com.dianping.cat.hadoop.dal.Graph;
import com.dianping.cat.hadoop.dal.GraphDao;
import com.dianping.cat.hadoop.dal.GraphEntity;
import com.dianping.cat.helper.CatString;
import com.dianping.cat.report.ReportPage;
import com.dianping.cat.report.page.model.problem.ProblemReportMerger;
import com.dianping.cat.report.page.model.spi.ModelPeriod;
import com.dianping.cat.report.page.model.spi.ModelRequest;
import com.dianping.cat.report.page.model.spi.ModelResponse;
import com.dianping.cat.report.page.model.spi.ModelService;
import com.dianping.cat.report.page.trend.GraphItem;
import com.google.gson.Gson;
import com.site.dal.jdbc.DalException;
import com.site.lookup.annotation.Inject;
import com.site.lookup.util.StringUtils;
import com.site.web.mvc.PageHandler;
import com.site.web.mvc.annotation.InboundActionMeta;
import com.site.web.mvc.annotation.OutboundActionMeta;
import com.site.web.mvc.annotation.PayloadMeta;

public class Handler implements PageHandler<Context> {

	public static final long ONE_HOUR = 3600 * 1000L;

	private static final String ERROR = "errors";

	@Inject
	private JspViewer m_jspViewer;

	@Inject(type = ModelService.class, value = "problem")
	private ModelService<ProblemReport> m_service;

	@Inject
	private ServerConfigManager m_manager;

	@Inject
	private DailyreportDao dailyreportDao;

	@Inject
	private GraphDao graphDao;

	private DefaultDomParser problemParser = new DefaultDomParser();

	private Gson gson = new Gson();

	private int getHour(long date) {
		Calendar cal = Calendar.getInstance();

		cal.setTimeInMillis(date);
		return cal.get(Calendar.HOUR_OF_DAY);
	}

	private String getIpAddress(ProblemReport report, Payload payload) {
		Map<String, Machine> machines = report.getMachines();
		String ip = payload.getIpAddress();

		if ((ip == null || ip.length() == 0) && !machines.isEmpty()) {
			ip = machines.keySet().iterator().next();
		}

		return ip;
	}

	private ProblemReport getHourlyReport(Payload payload) {
		String domain = payload.getDomain();
		String date = String.valueOf(payload.getDate());
		ModelRequest request = new ModelRequest(domain, payload.getPeriod()) //
		      .setProperty("date", date);
		if (!CatString.ALL_IP.equals(payload.getIpAddress())) {
			request.setProperty("ip", payload.getIpAddress());
		}
		if (!StringUtils.isEmpty(payload.getThreadId())) {
			request.setProperty("thread", payload.getThreadId());
		}
		if (m_service.isEligable(request)) {
			ModelResponse<ProblemReport> response = m_service.invoke(request);
			ProblemReport report = response.getModel();

			return report;
		} else {
			throw new RuntimeException("Internal error: no eligible problem service registered for " + request + "!");
		}
	}

	private void setDefaultThreshold(Model model, Payload payload) {
		Map<String, Domain> domains = m_manager.getLongConfigDomains();
		Domain d = domains.get(payload.getDomain());

		if (d != null) {
			int longUrlTime = d.getUrlThreshold();

			if (longUrlTime != 500 && longUrlTime != 1000 && longUrlTime != 2000 && longUrlTime != 3000
			      && longUrlTime != 4000 && longUrlTime != 5000) {
				double sec = (double) (longUrlTime) / (double) 1000;
				NumberFormat nf = new DecimalFormat("#.##");
				String option = "<option value=\"" + longUrlTime + "\"" + ">" + nf.format(sec) + " Sec</option>";

				model.setDefaultThreshold(option);
			}
		}
	}

	@Override
	@PayloadMeta(Payload.class)
	@InboundActionMeta(name = "p")
	public void handleInbound(Context ctx) throws ServletException, IOException {
		// display only, no action here
	}

	@Override
	@OutboundActionMeta(name = "p")
	public void handleOutbound(Context ctx) throws ServletException, IOException {
		Model model = new Model(ctx);
		Payload payload = ctx.getPayload();
		normalize(model, payload);
		ProblemReport report = null;
		ProblemStatistics problemStatistics = null;
		String ip = model.getIpAddress();

		switch (payload.getAction()) {
		case ALL:
			model.setIpAddress(CatString.ALL_IP);
			report = getHourlyReport(payload);
			model.setReport(report);
			problemStatistics = new ProblemStatistics().displayByAllIps(report, payload.getLongTime(),
			      payload.getLinkCount());
			model.setAllStatistics(problemStatistics);
			break;
		case HISTORY:
			report = showSummarizeReport(model, payload);
			if (ip.equals(CatString.ALL_IP)) {
				problemStatistics = new ProblemStatistics().displayByAllIps(report, payload.getLongTime(),
				      payload.getLinkCount());
			} else {
				problemStatistics = new ProblemStatistics().displayByIp(report, model.getIpAddress(),
				      payload.getLongTime(), payload.getLinkCount());
			}
			model.setReport(report);
			model.setAllStatistics(problemStatistics);
			break;
		case HISTORY_GRAPH:
			buildTrendGraph(model, payload);
			break;
		case GROUP:
			report = showHourlyReport(model, payload);
			if (report != null) {
				model.setGroupLevelInfo(new GroupLevelInfo(model).display(report));
			}
			model.setAllStatistics(new ProblemStatistics().displayByIp(report, model.getIpAddress(),
			      payload.getLongTime(), payload.getLinkCount()));
			break;
		case THREAD:
			String groupName = payload.getGroupName();
			report = showHourlyReport(model, payload);
			model.setGroupName(groupName);

			if (report != null) {
				model.setThreadLevelInfo(new ThreadLevelInfo(model, groupName).display(report));
			}
			model.setAllStatistics(new ProblemStatistics().displayByIp(report, model.getIpAddress(),
			      payload.getLongTime(), payload.getLinkCount()));
			break;
		case DETAIL:
			showDetail(model, payload);
			break;
		case MOBILE:
			if (ip.equals(CatString.ALL_IP)) {
				report = getHourlyReport(payload);
				problemStatistics = new ProblemStatistics().displayByAllIps(report, payload.getLongTime(),
				      payload.getLinkCount());
				problemStatistics.setIps(new ArrayList<String>(report.getIps()));
				String response = gson.toJson(problemStatistics);
				model.setMobileResponse(response);
			} else {
				report = showHourlyReport(model, payload);
				model.setAllStatistics(new ProblemStatistics().displayByIp(report, model.getIpAddress(),
				      payload.getLongTime(), payload.getLinkCount()));
				ProblemStatistics statistics = model.getAllStatistics();
				statistics.setIps(new ArrayList<String>(report.getIps()));
				model.setMobileResponse(gson.toJson(statistics));
			}
		}
		m_jspViewer.view(ctx, model);
	}

	private void buildTrendGraph(Model model, Payload payload) {
		Date start = payload.getHistoryStartDate();
		Date end = payload.getHistoryEndDate();
		int size = (int) ((end.getTime() - start.getTime()) / (60 * 1000));

		GraphItem item = new GraphItem();
		item.setStart(start);

		double[] data = getGraphData(model, payload).get(ERROR);
		String type = payload.getType();
		String status = payload.getStatus();
		item.setTitles(StringUtils.isEmpty(status) ? type : status);
		item.addValue(data);
		item.setSize(size);
		model.setErrorsTrend(item.getJsonString());
	}

	private ProblemReport showSummarizeReport(Model model, Payload payload) {
		String domain = model.getDomain();
		Date start = payload.getHistoryStartDate();
		Date end = payload.getHistoryEndDate();

		ProblemReport problemReport = null;
		try {

			List<Dailyreport> reports = dailyreportDao.findAllByDomainNameDuration(start, end, domain, "problem",
			      DailyreportEntity.READSET_FULL);
			ProblemReportMerger merger = new ProblemReportMerger(new ProblemReport(domain));
			for (Dailyreport report : reports) {
				String xml = report.getContent();
				ProblemReport reportModel = problemParser.parse(xml);
				reportModel.accept(merger);
			}
			problemReport = merger == null ? null : merger.getProblemReport();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return problemReport;
	}

	public Map<String, double[]> getGraphData(Model model, Payload payload) {
		Date start = payload.getHistoryStartDate();
		Date end = payload.getHistoryEndDate();
		String domain = model.getDomain();
		String type = payload.getType();
		String status = payload.getStatus();
		String ip = model.getIpAddress();
		String queryIP = "All".equals(ip) == true ? "all" : ip;
		List<Graph> graphs = new ArrayList<Graph>();

		try {
			graphs = this.graphDao.findByDomainNameIpDuration(start, end, queryIP, domain, "problem",
			      GraphEntity.READSET_FULL);
		} catch (DalException e) {
			e.printStackTrace();
		}
		Map<String, double[]> result = buildGraphDates(start, end, type, status, graphs);
		return result;
	}

	public void normalize(Model model, Payload payload) {
		if (StringUtils.isEmpty(payload.getDomain())) {
			payload.setDomain(m_manager.getConsoleDefaultDomain());
		}
		setDefaultThreshold(model, payload);

		Map<String, Domain> domains = m_manager.getLongConfigDomains();
		Domain d = domains.get(payload.getDomain());

		if (d != null && payload.getRealLongTime() == 0) {
			payload.setLongTime(d.getUrlThreshold());
		}

		String ip = payload.getIpAddress();
		if (StringUtils.isEmpty(ip)) {
			ip = CatString.ALL_IP;
		}
		model.setIpAddress(ip);
		model.setLongDate(payload.getDate());
		model.setAction(payload.getAction());
		model.setPage(ReportPage.PROBLEM);
		model.setDisplayDomain(payload.getDomain());
		model.setThreshold(payload.getLongTime());
		if (payload.getPeriod().isCurrent()) {
			model.setCreatTime(new Date());
		} else {
			model.setCreatTime(new Date(payload.getDate() + 60 * 60 * 1000 - 1000));
		}
		if (payload.getAction() == Action.HISTORY) {
			String type = payload.getReportType();
			if (type == null || type.length() == 0) {
				payload.setReportType("day");
			}
			model.setReportType(payload.getReportType());
			payload.computeStartDate();
			payload.defaultIsYesterday();
			model.setLongDate(payload.getDate());
		}
	}

	private void showDetail(Model model, Payload payload) {
		String ipAddress = payload.getIpAddress();
		model.setLongDate(payload.getDate());
		model.setIpAddress(ipAddress);
		model.setGroupName(payload.getGroupName());
		model.setCurrentMinute(payload.getMinute());
		model.setThreadId(payload.getThreadId());

		ProblemReport report = getHourlyReport(payload);

		if (report == null) {
			return;
		}
		model.setReport(report);
		model.setProblemStatistics(new ProblemStatistics().displayByGroupOrThread(report, model, payload));
	}

	private ProblemReport showHourlyReport(Model model, Payload payload) {
		ModelPeriod period = payload.getPeriod();
		if (period.isFuture()) {
			model.setLongDate(payload.getCurrentDate());
		} else {
			model.setLongDate(payload.getDate());
		}

		if (period.isCurrent() || period.isFuture()) {
			Calendar cal = Calendar.getInstance();
			int minute = cal.get(Calendar.MINUTE);

			model.setLastMinute(minute);
		} else {
			model.setLastMinute(59);
		}
		model.setHour(getHour(model.getLongDate()));
		ProblemReport report = getHourlyReport(payload);
		if (report != null) {
			String ip = getIpAddress(report, payload);

			model.setIpAddress(ip);
			model.setReport(report);
		}
		return report;
	}

	private Map<String, double[]> buildGraphDates(Date start, Date end, String type, String status, List<Graph> graphs) {
		Map<String, double[]> result = new HashMap<String, double[]>();
		int size = (int) ((end.getTime() - start.getTime()) / ONE_HOUR) * 60;
		double[] errors = new double[size];

		if (!StringUtils.isEmpty(type) && StringUtils.isEmpty(status)) {
			for (Graph graph : graphs) {
				int indexOfperiod = (int) ((graph.getPeriod().getTime() - start.getTime()) / ONE_HOUR * 60);
				String summaryContent = graph.getSummaryContent();
				String[] allLines = summaryContent.split("\n");
				for (int j = 0; j < allLines.length; j++) {
					String[] records = allLines[j].split("\t");
					String dbType = records[SummaryOrder.TYPE.ordinal()];
					if (dbType.equals(type)) {
						String[] values = records[SummaryOrder.DETAIL.ordinal()].split(",");
						for (int k = 0; k < values.length; k++) {
							errors[indexOfperiod + k] = Double.parseDouble(values[k]);
						}
					}
				}
			}
		} else if (!StringUtils.isEmpty(type) && !StringUtils.isEmpty(status)) {
			for (Graph graph : graphs) {
				int indexOfperiod = (int) ((graph.getPeriod().getTime() - start.getTime()) / ONE_HOUR * 60);
				String detailContent = graph.getDetailContent();
				String[] allLines = detailContent.split("\n");
				for (int j = 0; j < allLines.length; j++) {
					String[] records = allLines[j].split("\t");
					String dbStatus = records[DetailOrder.STATUS.ordinal()];
					String dbType = records[DetailOrder.TYPE.ordinal()];
					if (status.equals(dbStatus) && type.equals(dbType)) {
						String[] values = records[DetailOrder.DETAIL.ordinal()].split(",");
						for (int k = 0; k < values.length; k++) {
							errors[indexOfperiod + k] = Double.parseDouble(values[k]);
						}
					}
				}
			}
		}
		result.put(ERROR, errors);
		return result;
	}

	public enum SummaryOrder {
		TYPE, TOTAL_COUNT, DETAIL
	}

	public enum DetailOrder {
		TYPE, STATUS, TOTAL_COUNT, DETAIL
	}
}
