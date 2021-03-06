package jp.co.sony.csl.dcoes.apis.main.app;

import io.vertx.ext.unit.TestContext;
import jp.co.sony.csl.dcoes.apis.main.factory.Factory;
import jp.co.sony.csl.dcoes.apis.main.test.AbstractApisTest;

import org.junit.Test;

/**
 * APIS 全体のテスト.
 * @author OES Project
 */
public class ApisTest extends AbstractApisTest {

	/**
	 *  deploy と undeploy が行える.
	 * @param context testcontext オブジェクト
	 */
	@Test
	public void testDeployAndUnDeploy(TestContext context) {
		Factory.initialize(context.asyncAssertSuccess(v -> {
			vertx.deployVerticle(Apis.class.getName(), context.asyncAssertSuccess(deploymentID -> {
				vertx.undeploy(deploymentID, context.asyncAssertSuccess());
			}));
		}));
	}

}