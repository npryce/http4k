package org.http4k.serverless.lambda.testing.setup

import org.http4k.aws.awsCliUserProfiles
import org.http4k.client.JavaHttpClient
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Status.Companion.OK
import org.http4k.serverless.lambda.testing.client.awsLambdaApiClient
import org.http4k.serverless.lambda.testing.client.restApiGatewayApiClient
import org.http4k.serverless.lambda.testing.setup.DeployServerAsLambdaForClientContract.functionName
import org.http4k.connect.amazon.apigateway.CreateDeployment
import org.http4k.connect.amazon.apigateway.DeploymentName
import org.http4k.connect.amazon.apigateway.createApi
import org.http4k.connect.amazon.apigateway.createIntegration
import org.http4k.connect.amazon.apigateway.createIntegrationResponse
import org.http4k.connect.amazon.apigateway.createMethod
import org.http4k.connect.amazon.apigateway.createResource
import org.http4k.connect.amazon.apigateway.createStage
import org.http4k.connect.amazon.apigateway.delete
import org.http4k.connect.amazon.apigateway.listApis
import org.http4k.connect.amazon.apigateway.listResources
import org.http4k.connect.amazon.apigatewayv2.ApiName
import org.http4k.connect.amazon.apigatewayv2.Stage
import org.http4k.connect.amazon.getOrThrow
import org.http4k.connect.amazon.lambda.model.LambdaIntegrationType.ApiGatewayRest
import org.http4k.connect.amazon.lambda.model.Region
import org.http4k.connect.amazon.lambda.list

object DeployRestApiGateway {

    fun deploy() {
        val config = awsCliUserProfiles().profile("http4k-integration-test")
        val region = Region(config.region)

        val functionName = functionName(ApiGatewayRest)

        val lambdaApi = config.awsLambdaApiClient()

        val functionArn = lambdaApi.list().find { it.name == functionName.value }?.arn
            ?: error("Lambda ${functionName.value} does not exist.")

        val apiGateway = config.restApiGatewayApiClient()

        val apis = apiGateway.listApis(region)

        apis.filter { it.name == apiName() }.forEach {
            println("Deleting ${it.apiId.value}")
            apiGateway.delete(it.apiId)
        }

        val api = apiGateway.createApi(apiName(), region)
        val rootResource = apiGateway.listResources(api.apiId)._embedded.item
        val proxyResource = apiGateway.createResource(api.apiId, rootResource)
        apiGateway.createMethod(api.apiId, proxyResource)
        apiGateway.createIntegration(api.apiId, proxyResource, functionArn, region)
        apiGateway.createIntegrationResponse(api.apiId, proxyResource)
        val deploymentId = apiGateway(CreateDeployment(api.apiId, DeploymentName(Stage.restDefault.stageName.value))).getOrThrow()
        apiGateway.createStage(api.apiId, Stage.restDefault, deploymentId)

        retryUntil(OK) {
            JavaHttpClient()(Request(GET, api.apiEndpoint.path("/default/empty"))).also { println(it.status) }
        }
    }

    fun apiName() = ApiName("http4k-rest-api")
}

fun main() {
    DeployRestApiGateway.deploy()
}
