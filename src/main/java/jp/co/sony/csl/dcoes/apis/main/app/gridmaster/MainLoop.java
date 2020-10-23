package jp.co.sony.csl.dcoes.apis.main.app.gridmaster;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import jp.co.sony.csl.dcoes.apis.common.Error;
import jp.co.sony.csl.dcoes.apis.common.ServiceAddress;
import jp.co.sony.csl.dcoes.apis.common.util.vertx.LocalExclusiveLock;
import jp.co.sony.csl.dcoes.apis.main.app.PolicyKeeping;
import jp.co.sony.csl.dcoes.apis.main.app.StateHandling;
import jp.co.sony.csl.dcoes.apis.main.app.gridmaster.main_loop.DealExecution;
import jp.co.sony.csl.dcoes.apis.main.app.gridmaster.main_loop.ErrorHandling;
import jp.co.sony.csl.dcoes.apis.main.app.gridmaster.main_loop.GlobalDataCalculation;
import jp.co.sony.csl.dcoes.apis.main.evaluation.safety.GlobalSafetyEvaluation;
import jp.co.sony.csl.dcoes.apis.main.util.ErrorUtil;

/**
 * 融通処理やエラー対応などの主業務を定期的に実行する Verticle.
 * {@link GridMaster} から起動される.
 * 以下の処理を定期的に実行する.
 * 1. {@link ErrorHandling#execute(Vertx, Handler)}
 * 2. {@link DealExecution#execute(Vertx, Handler)}
 * 3. {@link GlobalSafetyEvaluation#check(Vertx, io.vertx.core.json.JsonObject, io.vertx.core.json.JsonObject, Handler)}
 * 4. {@link GlobalDataCalculation#execute(Vertx, Handler)}
 * 5. {@link ErrorHandling#execute(Vertx, Handler)}
 * @author OES Project
 */
public class MainLoop extends AbstractVerticle {
	private static final Logger log = LoggerFactory.getLogger(MainLoop.class);

	/**
	 * GridMaster メインループの実行周期のデフォルト値 [ms].
	 * 値は {@value}.
	 */
	public static final Long DEFAULT_MAIN_LOOP_PERIOD_MSEC = 5000L;

	private static final LocalExclusiveLock exclusiveLock_ = new LocalExclusiveLock(MainLoop.class.getName());
	/**
	 * 排他ロックを優先的に獲得する.
	 * completionHandler の {@link AsyncResult#result()} で受け取る.
	 * @param vertx vertx オブジェクト
	 * @param completionHandler the completion handler
	 */
	public static void acquirePrivilegedExclusiveLock(Vertx vertx, Handler<AsyncResult<LocalExclusiveLock.Lock>> completionHandler) {
		exclusiveLock_.acquire(vertx, true, completionHandler);
	}
	/**
	 * 排他ロックを獲得する.
	 * completionHandler の {@link AsyncResult#result()} で受け取る.
	 * @param vertx vertx オブジェクト
	 * @param completionHandler the completion handler
	 */
	public static void acquireExclusiveLock(Vertx vertx, Handler<AsyncResult<LocalExclusiveLock.Lock>> completionHandler) {
		exclusiveLock_.acquire(vertx, completionHandler);
	}
	/**
	 * 排他ロックをリセットする.
	 * @param vertx vertx オブジェクト
	 */
	public static void resetExclusiveLock(Vertx vertx) {
		exclusiveLock_.reset(vertx);
	}

	private long mainLoopTimerId_ = 0L;
	private boolean stopped_ = false;

	/**
	 * 起動時に呼び出される.
	 * 定期的に処理を実行するタイマを起動する.
	 * @param startFuture {@inheritDoc}
	 * @throws Exception {@inheritDoc}
	 */
	@Override public void start(Future<Void> startFuture) throws Exception {
		mainLoopTimerHandler_(0L);
		if (log.isTraceEnabled()) log.trace("started : " + deploymentID());
		startFuture.complete();
	}

	/**
	 * 停止時に呼び出される.
	 * タイマを止めるためのフラグを立てる.
	 * @throws Exception {@inheritDoc}
	 */
	@Override public void stop() throws Exception {
		stopped_ = true;
		if (log.isTraceEnabled()) log.trace("stopped : " + deploymentID());
	}

	////

	/**
	 * GridMaster メインループ実行タイマ設定.
	 * 待ち時間は {@code POLICY.gridMaster.mainLoopPeriodMsec} ( デフォルト値 {@link #DEFAULT_MAIN_LOOP_PERIOD_MSEC} ).
	 */
	private void setMainLoopTimer_() {
		Long delay = PolicyKeeping.cache().getLong(DEFAULT_MAIN_LOOP_PERIOD_MSEC, "gridMaster", "mainLoopPeriodMsec");
		setMainLoopTimer_(delay);
	}
	/**
	 * GridMaster メインループ実行タイマ設定.
	 * @param delay 周期 [ms]
	 */
	private void setMainLoopTimer_(long delay) {
		mainLoopTimerId_ = vertx.setTimer(delay, this::mainLoopTimerHandler_);
	}
	/**
	 * GridMaster メインループ実行タイマ処理.
	 * @param timerId タイマ ID
	 */
	private void mainLoopTimerHandler_(Long timerId) {
		if (stopped_) return;
		if (null == timerId || timerId.longValue() != mainLoopTimerId_) {
			ErrorUtil.report(vertx, Error.Category.LOGIC, Error.Extent.LOCAL, Error.Level.WARN, "illegal timerId : " + timerId + ", mainLoopTimerId_ : " + mainLoopTimerId_);
			return;
		}
		if (!StateHandling.isInOperation()) {
			// まだ起動中なのでスルーしタイマだけ再設定
			setMainLoopTimer_();
		} else {
			// MainLoop 実行中に GM 停止が起きないよう排他ロックを獲得
			acquireExclusiveLock(vertx, resExclusiveLock -> {
				if (resExclusiveLock.succeeded()) {
					LocalExclusiveLock.Lock lock = resExclusiveLock.result();
					doMainLoopWithExclusiveLock_(resDoMainLoopWithExclusiveLock -> {
						lock.release();
						setMainLoopTimer_();
					});
				} else {
					ErrorUtil.report(vertx, Error.Category.LOGIC, Error.Extent.LOCAL, Error.Level.WARN, resExclusiveLock.cause());
					setMainLoopTimer_();
				}
			});
		}
	}
	private void doMainLoopWithExclusiveLock_(Handler<AsyncResult<Void>> completionHandler) {
		if (stopped_) {
			completionHandler.handle(Future.succeededFuture());
		} else {
			ErrorHandling.execute(vertx, resErrorHandling_before -> {
				DealExecution.execute(vertx, resDealExecution -> {
					GlobalSafetyEvaluation.check(vertx, PolicyKeeping.cache().jsonObject(), DealExecution.unitDataCache.jsonObject(), resSafetyEvaluation -> {
						GlobalDataCalculation.execute(vertx, resGlobalDataCalculation -> {
							// 最後にエラー処理をもう一度実行
							ErrorHandling.execute(vertx, resErrorHandling_after -> {
								// 融通処理などで状況が変わっているだろうから GridMaster を適切に配置し直す
								vertx.eventBus().send(ServiceAddress.Mediator.gridMasterEnsuring(), null);
								completionHandler.handle(Future.succeededFuture());
							});
						});
					});
				});
			});
		}
	}

}
