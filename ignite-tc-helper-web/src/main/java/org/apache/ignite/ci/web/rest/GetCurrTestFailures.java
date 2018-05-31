package org.apache.ignite.ci.web.rest;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.xml.ws.spi.http.HttpContext;

import org.apache.ignite.Ignite;
import org.apache.ignite.ci.BuildChainProcessor;
import org.apache.ignite.ci.HelperConfig;
import org.apache.ignite.ci.IAnalyticsEnabledTeamcity;
import org.apache.ignite.ci.ITcHelper;
import org.apache.ignite.ci.ITeamcity;
import org.apache.ignite.ci.IgnitePersistentTeamcity;
import org.apache.ignite.ci.analysis.FullChainRunCtx;
import org.apache.ignite.ci.analysis.mode.LatestRebuildMode;
import org.apache.ignite.ci.analysis.mode.ProcessLogsMode;
import org.apache.ignite.ci.conf.BranchTracked;
import org.apache.ignite.ci.tcmodel.hist.BuildRef;
import org.apache.ignite.ci.web.BackgroundUpdater;
import org.apache.ignite.ci.web.CtxListener;
import org.apache.ignite.ci.web.auth.DummyCredentials;
import org.apache.ignite.ci.web.auth.ICredentialsProv;
import org.apache.ignite.ci.web.rest.model.current.*;
import org.apache.ignite.ci.web.rest.parms.FullQueryParams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.apache.ignite.ci.BuildChainProcessor.loadChainsContext;

@Path(GetCurrTestFailures.CURRENT)
@Produces(MediaType.APPLICATION_JSON)
public class GetCurrTestFailures {
    public static final String CURRENT = "current";
    public static final String TEST_FAILURES_SUMMARY_CACHE_NAME = CURRENT + "TestFailuresSummary";
    @Context
    private ServletContext context;

    @Context
    HttpServletRequest request;

    @GET
    @Path("failures/updates")
    public UpdateInfo getTestFailsUpdates(@Nullable @QueryParam("branch") String branchOrNull,
        @Nullable @QueryParam("checkAllLogs") Boolean checkAllLogs) {
        return new UpdateInfo().copyFrom(getTestFails(branchOrNull, checkAllLogs));
    }

    @GET
    @Path("failures/txt")
    @Produces(MediaType.TEXT_PLAIN)
    public String getTestFailsText(@Nullable @QueryParam("branch") String branchOrNull,
        @Nullable @QueryParam("checkAllLogs") Boolean checkAllLogs) {
        return getTestFails(branchOrNull, checkAllLogs).toString();
    }

    @GET
    @Path("failures")
    public TestFailuresSummary getTestFails(
        @Nullable @QueryParam("branch") String branchOrNull,
        @Nullable @QueryParam("checkAllLogs") Boolean checkAllLogs) {

        final BackgroundUpdater updater = CtxListener.getBackgroundUpdater(context);

        FullQueryParams param = new FullQueryParams();
        param.setBranch(branchOrNull);
        param.setCheckAllLogs(checkAllLogs);
        return updater.get(TEST_FAILURES_SUMMARY_CACHE_NAME, param,
            (k) -> getTestFailsNoCache(k.getBranch(), k.getCheckAllLogs()), true);
    }

    @GET
    @Path("failuresNoCache")
    @NotNull public TestFailuresSummary getTestFailsNoCache(
        @Nullable @QueryParam("branch") String branch,
        @Nullable @QueryParam("checkAllLogs") Boolean checkAllLogs) {

        final ITcHelper helper = CtxListener.getTcHelper(context);
        final ICredentialsProv dummyCredentials = DummyCredentials.create(request);

        final TestFailuresSummary res = new TestFailuresSummary();
        final AtomicInteger runningUpdates = new AtomicInteger();

        final String branchNn = isNullOrEmpty(branch) ? FullQueryParams.DEFAULT_BRANCH_NAME : branch;
        final BranchTracked tracked = HelperConfig.getTrackedBranches().getBranchMandatory(branchNn);

        tracked.chains.stream().parallel()
            .map(chainTracked -> {
                final String srvId = chainTracked.serverId;
                final String branchForTc = chainTracked.getBranchForRestMandatory();
                final String failRateBranch = branchForTc; //branch is tracked, so fail rate should be taken from branch data

                final ChainAtServerCurrentStatus chainStatus = new ChainAtServerCurrentStatus(srvId, branchForTc);
                try (IAnalyticsEnabledTeamcity teamcity = helper.server(srvId, dummyCredentials)) {
                    Optional<FullChainRunCtx> pubCtx = loadChainsContext(teamcity,
                        chainTracked.getSuiteIdMandatory(),
                        branchForTc,
                        LatestRebuildMode.LATEST,
                        (checkAllLogs != null && checkAllLogs) ? ProcessLogsMode.ALL : ProcessLogsMode.SUITE_NOT_COMPLETE,
                        failRateBranch);

                    pubCtx.ifPresent(ctx -> {
                        int cnt = (int)ctx.getRunningUpdates().count();
                        if (cnt > 0)
                            runningUpdates.addAndGet(cnt);

                        chainStatus.initFromContext(teamcity, ctx, teamcity, failRateBranch);
                    });
                }
                return chainStatus;
            })
            .sorted(Comparator.comparing(ChainAtServerCurrentStatus::serverName))
            .forEach(res::addChainOnServer);

        res.postProcess(runningUpdates.get());

        helper.issueDetector().registerIssuesLater(res, helper);

        return res;
    }

    @GET
    @Path("pr/updates")
    public UpdateInfo getPrFailuresUpdates(
        @Nullable @QueryParam("serverId") String serverId,
        @Nonnull @QueryParam("suiteId") String suiteId,
        @Nonnull @QueryParam("branchForTc") String branchForTc,
        @Nonnull @QueryParam("action") String action,
        @Nullable @QueryParam("count") Integer count) {

        return new UpdateInfo().copyFrom(getPrFailures(serverId, suiteId, branchForTc, action, count));
    }

    @GET
    @Path("pr")
    public TestFailuresSummary getPrFailures(
        @Nullable @QueryParam("serverId") String serverId,
        @Nonnull @QueryParam("suiteId") String suiteId,
        @Nonnull @QueryParam("branchForTc") String branchForTc,
        @Nonnull @QueryParam("action") String action,
        @Nullable @QueryParam("count") Integer count) {

        final BackgroundUpdater updater = CtxListener.getBackgroundUpdater(context);

        final FullQueryParams key = new FullQueryParams(serverId, suiteId, branchForTc, action, count);

        return updater.get(CURRENT + "PrFailures", key,
            (k) -> getPrFailuresNoCache(k.getServerId(), k.getSuiteId(), k.getBranchForTc(), k.getAction(), k.getCount()),
            true);
    }

    @GET
    @Path("prNoCache")
    @NotNull public TestFailuresSummary getPrFailuresNoCache(
        @Nullable @QueryParam("serverId") String srvId,
        @Nonnull @QueryParam("suiteId") String suiteId,
        @Nonnull @QueryParam("branchForTc") String branchForTc,
        @Nonnull @QueryParam("action") String action,
        @Nullable @QueryParam("count") Integer count) {

        final TestFailuresSummary res = new TestFailuresSummary();
        final AtomicInteger runningUpdates = new AtomicInteger();

        //using here non persistent TC allows to skip update statistic
        try (IgnitePersistentTeamcity teamcity = new IgnitePersistentTeamcity(CtxListener.getIgnite(context), srvId)) {
            teamcity.setExecutor(CtxListener.getPool(context));

            LatestRebuildMode rebuild;
            if (FullQueryParams.HISTORY.equals(action))
                rebuild = LatestRebuildMode.ALL;
            else if (FullQueryParams.LATEST.equals(action))
                rebuild = LatestRebuildMode.LATEST;
            else if (FullQueryParams.CHAIN.equals(action))
                rebuild = LatestRebuildMode.NONE;
            else
                rebuild = LatestRebuildMode.LATEST;

            List<BuildRef> finishedBuilds = teamcity.getFinishedBuildsIncludeSnDepFailed(
                suiteId,
                branchForTc);

            long limit;
            if (rebuild == LatestRebuildMode.ALL)
                limit = count == null ? 10 : count;
            else
                limit = 1;

            final List<BuildRef> chains = finishedBuilds.stream()
                .filter(ref -> !ref.isFakeStub())
                .sorted(Comparator.comparing(BuildRef::getId).reversed())
                .limit(limit)
                .filter(b -> b.getId() != null).collect(Collectors.toList());

            boolean singleBuild = rebuild != LatestRebuildMode.ALL;
            ProcessLogsMode logs = singleBuild
                ? ProcessLogsMode.SUITE_NOT_COMPLETE
                : ProcessLogsMode.DISABLED;

            String failRateBranch = ITeamcity.DEFAULT;

            Optional<FullChainRunCtx> pubCtx = BuildChainProcessor.processBuildChains(teamcity, rebuild, chains,
                logs,
                singleBuild,
                true, teamcity, failRateBranch);

            final ChainAtServerCurrentStatus chainStatus = new ChainAtServerCurrentStatus(teamcity.serverId(), branchForTc);

            pubCtx.ifPresent(ctx -> {
                if (ctx.isFakeStub())
                    chainStatus.setBuildNotFound(true);
                else {
                    int cnt = (int)ctx.getRunningUpdates().count();
                    if (cnt > 0)
                        runningUpdates.addAndGet(cnt);

                    //fail rate reference is always default (master)
                    chainStatus.initFromContext(teamcity, ctx, teamcity, failRateBranch);
                }
            });

            res.addChainOnServer(chainStatus);
        }

        res.postProcess(runningUpdates.get());

        return res;
    }

}
