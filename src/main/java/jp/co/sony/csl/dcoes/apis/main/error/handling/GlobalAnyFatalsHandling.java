package jp.co.sony.csl.dcoes.apis.main.error.handling;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jp.co.sony.csl.dcoes.apis.main.error.action.Scram;
import jp.co.sony.csl.dcoes.apis.main.error.action.ShutdownAll;

/**
 * エラー対応の実クラス.
 * - 範囲 : {@link jp.co.sony.csl.dcoes.apis.common.Error.Extent#GLOBAL}
 * - 種類 : すべて
 * - 深刻さ : {@link jp.co.sony.csl.dcoes.apis.common.Error.Level#FATAL}
 * - 処理内容 :
 *   1. SCRAM
 *   2. 全ユニットをシャットダウン
 * @author OES Project
 */
public class GlobalAnyFatalsHandling extends AbstractErrorsHandling {

	/**
	 * インスタンスを生成する.
	 * @param vertx vertx オブジェクト
	 * @param policy POLICY オブジェクト. 処理中に変更されても影響しないように {@link jp.co.sony.csl.dcoes.apis.main.app.user.ErrorHandling} あるいは {@link jp.co.sony.csl.dcoes.apis.main.app.gridmaster.main_loop.ErrorHandling} でコピーしたものが渡される.
	 * @param errors 処理対象のエラーのリスト
	 */
	public GlobalAnyFatalsHandling(Vertx vertx, JsonObject policy, JsonArray errors) {
		super(vertx, policy, errors);
	}

	/**
	 * {@inheritDoc}
	 * 1. SCRAM
	 * 2. 全ユニットをシャットダウン
	 */
	@Override protected void doHandle(Handler<AsyncResult<Void>> completionHandler) {
		new Scram(vertx_, policy_, logMessages_).action(r -> {
			new ShutdownAll(vertx_, policy_, logMessages_).action(completionHandler);
		});
	}

}
