package jp.co.sony.csl.dcoes.apis.main.app.mediator;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import jp.co.sony.csl.dcoes.apis.common.Error;
import jp.co.sony.csl.dcoes.apis.common.ServiceAddress;
import jp.co.sony.csl.dcoes.apis.main.app.PolicyKeeping;
import jp.co.sony.csl.dcoes.apis.main.util.ApisConfig;
import jp.co.sony.csl.dcoes.apis.main.util.ErrorUtil;

/**
 * 自ユニットからのリクエストを処理する Verticle.
 * {@link Mediator} Verticle から起動される.
 * {@link Negotiation} Verticle を起動し処理を任せる.
 * @author OES Project
 */
public class InternalRequestHandling extends AbstractVerticle {
	private static final Logger log = LoggerFactory.getLogger(InternalRequestHandling.class);

	/**
	 * 起動時に呼び出される.
	 * {@link io.vertx.core.eventbus.EventBus} サービスを起動する.
	 * @param startFuture {@inheritDoc}
	 * @throws Exception {@inheritDoc}
	 */
	@Override public void start(Future<Void> startFuture) throws Exception {
		startInternalRequestHandlingService_(resInternalRequestHandling -> {
			if (resInternalRequestHandling.succeeded()) {
				if (log.isTraceEnabled()) log.trace("started : " + deploymentID());
				startFuture.complete();
			} else {
				startFuture.fail(resInternalRequestHandling.cause());
			}
		});
	}

	/**
	 * 停止時に呼び出される.
	 * @throws Exception {@inheritDoc}
	 */
	@Override public void stop() throws Exception {
		if (log.isTraceEnabled()) log.trace("stopped : " + deploymentID());
	}

	////

	/**
	 * {@link io.vertx.core.eventbus.EventBus} サービス起動.
	 * アドレス : {@link ServiceAddress.Mediator#internalRequest()}
	 * 範囲 : ローカル
	 * 処理 : 自ユニットからリクエストを受け取り融通交渉を実行する.
	 * 　　   融通交渉は {@link Negotiation} を起動し処理を任せる.
	 * メッセージボディ : リクエスト情報 [{@link JsonObject}]
	 * メッセージヘッダ : なし
	 * レスポンス : deploymentID [{@link String}]
	 * @param completionHandler the completion handler
	 */
	private void startInternalRequestHandlingService_(Handler<AsyncResult<Void>> completionHandler) {
		vertx.eventBus().<JsonObject>localConsumer(ServiceAddress.Mediator.internalRequest(), req -> {
			JsonObject request = req.body();
			if (log.isDebugEnabled()) log.debug("request received : " + request);
			if (request != null) {
				Integer dealAmountMinWh = PolicyKeeping.cache().getInteger(0, "mediator", "deal", "amountMinWh");
				Integer amountWh = request.getInteger("amountWh", 0);
				if (dealAmountMinWh < amountWh) {
					// 自ユニットからのリクエストの融通電力量 ( amountWh ) が最低値 ( POLICY.mediator.deal.amountMinWh ) より大きければリクエストを出す
					request.put("dealGridCurrentA", PolicyKeeping.cache().getFloat(0F, "mediator", "deal", "gridCurrentA"));
					request.put("unitId", ApisConfig.unitId());
					// Negotiation を作ってお任せする
					vertx.deployVerticle(new Negotiation(request), resDeployNegotiation -> {
						if (resDeployNegotiation.succeeded()) {
							req.reply(resDeployNegotiation.result());
						} else {
							ErrorUtil.reportAndFail(vertx, Error.Category.FRAMEWORK, Error.Extent.LOCAL, Error.Level.FATAL, resDeployNegotiation.cause(), req);
						}
					});
				} else {
					String msg = "request amount : " + amountWh + " ; less than dealAmountMinWh : " + dealAmountMinWh;
					if (log.isDebugEnabled()) log.debug(msg);
					req.fail(-1, msg);
				}
			} else {
				ErrorUtil.reportAndFail(vertx, Error.Category.LOGIC, Error.Extent.LOCAL, Error.Level.ERROR, "request is null", req);
			}
		}).completionHandler(completionHandler);
	}

}
