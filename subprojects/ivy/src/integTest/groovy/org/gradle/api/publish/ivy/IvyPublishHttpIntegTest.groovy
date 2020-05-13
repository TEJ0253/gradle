/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.publish.ivy

import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.MetaDataParser
import org.gradle.integtests.fixtures.ToBeFixedForInstantExecution
import org.gradle.integtests.fixtures.executer.ProgressLoggingFixture
import org.gradle.internal.credentials.DefaultPasswordCredentials
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.server.http.AuthScheme
import org.gradle.test.fixtures.server.http.HttpServer
import org.gradle.test.fixtures.server.http.IvyHttpModule
import org.gradle.test.fixtures.server.http.IvyHttpRepository
import org.gradle.util.GradleVersion
import org.hamcrest.CoreMatchers
import org.junit.Rule
import org.mortbay.jetty.HttpStatus
import spock.lang.Unroll

import static org.gradle.test.matchers.UserAgentMatcher.matchesNameAndVersion
import static org.gradle.util.Matchers.matchesRegexp

class IvyPublishHttpIntegTest extends AbstractIvyPublishIntegTest {
    private static final int HTTP_UNRECOVERABLE_ERROR = 415
    private static final String BAD_CREDENTIALS = '''
credentials {
    username 'testuser'
    password 'bad'
}
'''
    @Rule ProgressLoggingFixture progressLogging = new ProgressLoggingFixture(executer, temporaryFolder)
    @Rule HttpServer server = new HttpServer()

    private IvyHttpModule module
    private IvyHttpRepository ivyHttpRepo

    def setup() {
        ivyHttpRepo = new IvyHttpRepository(server, ivyRepo)
        module = ivyHttpRepo.module("org.gradle", "publish", "2")
        server.expectUserAgent(matchesNameAndVersion("Gradle", GradleVersion.current().getVersion()))
        server.start()

        settingsFile << 'rootProject.name = "publish"'
    }

    @Unroll
    @ToBeFixedForInstantExecution
    def "can publish to unauthenticated HTTP repository (extra checksums = #extraChecksums)"() {
        given:
        buildFile << """
            apply plugin: 'java'
            apply plugin: 'ivy-publish'

            version = '2'
            group = 'org.gradle'

            publishing {
                repositories {
                    ivy { url "${ivyHttpRepo.uri}" }
                }
                publications {
                    ivy(IvyPublication) {
                        from components.java
                    }
                }
            }
        """

        if (!extraChecksums) {
            executer.withArgument("-Dorg.gradle.internal.publish.checksums.insecure=true")
            module.withoutExtraChecksums()
        }

        and:
        module.jar.expectPut()
        module.jar.sha1.expectPut()
        if (extraChecksums) {
            module.jar.sha256.expectPut()
            module.jar.sha512.expectPut()
        }
        module.ivy.expectPut(HttpStatus.ORDINAL_201_Created)
        module.ivy.sha1.expectPut(HttpStatus.ORDINAL_201_Created)
        if (extraChecksums) {
            module.ivy.sha256.expectPut(HttpStatus.ORDINAL_201_Created)
            module.ivy.sha512.expectPut(HttpStatus.ORDINAL_201_Created)
        }
        module.moduleMetadata.expectPut()
        module.moduleMetadata.sha1.expectPut()
        if (extraChecksums) {
            module.moduleMetadata.sha256.expectPut()
            module.moduleMetadata.sha512.expectPut()
        }

        when:
        succeeds 'publish'

        then:
        module.assertMetadataAndJarFilePublished()
        module.jarFile.assertIsCopyOf(file('build/libs/publish-2.jar'))

        and:
        progressLogging.uploadProgressLogged(module.moduleMetadata.uri)
        progressLogging.uploadProgressLogged(module.ivy.uri)
        progressLogging.uploadProgressLogged(module.jar.uri)

        where:
        extraChecksums << [true, false]
    }

    @ToBeFixedForInstantExecution
    def "can publish to a repository even if it doesn't support sha256/sha512 signatures"() {
        given:
        buildFile << """
            apply plugin: 'java'
            apply plugin: 'ivy-publish'

            version = '2'
            group = 'org.gradle'

            publishing {
                repositories {
                    ivy { url "${ivyHttpRepo.uri}" }
                }
                publications {
                    ivy(IvyPublication) {
                        from components.java
                    }
                }
            }
        """

        and:
        maxUploadAttempts = 1

        when:
        module.artifact.expectPut()
        module.artifact.sha1.expectPut()
        module.artifact.sha256.expectPutBroken()
        module.artifact.sha512.expectPutBroken()
        module.ivy.expectPut()
        module.ivy.sha1.expectPut()
        module.ivy.sha256.expectPutBroken()
        module.ivy.sha512.expectPutBroken()
        module.moduleMetadata.expectPut()
        module.moduleMetadata.sha1.expectPut()
        module.moduleMetadata.sha256.expectPutBroken()
        module.moduleMetadata.sha512.expectPutBroken()

        then:
        succeeds 'publish'
        outputContains("Remote repository doesn't support sha-256")
        outputContains("Remote repository doesn't support sha-512")
    }

    @Unroll
    @ToBeFixedForInstantExecution
    def "can publish to authenticated repository using #authScheme auth"() {
        given:
        buildFile << """
            apply plugin: 'java'
            apply plugin: 'ivy-publish'

            version = '2'
            group = 'org.gradle'

            publishing {
                repositories {
                    ivy {
                        credentials {
                            username 'testuser'
                            password 'password'
                        }
                        url "${ivyHttpRepo.uri}"
                    }
                }
                publications {
                    ivy(IvyPublication) {
                        from components.java
                    }
                }
            }
        """

        and:
        server.authenticationScheme = authScheme
        module.jar.expectPut('testuser', 'password')
        module.jar.sha1.expectPut('testuser', 'password')
        module.jar.sha256.expectPut('testuser', 'password')
        module.jar.sha512.expectPut('testuser', 'password')
        module.ivy.expectPut('testuser', 'password')
        module.ivy.sha1.expectPut('testuser', 'password')
        module.ivy.sha256.expectPut('testuser', 'password')
        module.ivy.sha512.expectPut('testuser', 'password')
        module.moduleMetadata.expectPut('testuser', 'password')
        module.moduleMetadata.sha1.expectPut('testuser', 'password')
        module.moduleMetadata.sha256.expectPut('testuser', 'password')
        module.moduleMetadata.sha512.expectPut('testuser', 'password')

        when:
        run 'publish'

        then:
        module.assertMetadataAndJarFilePublished()
        module.jarFile.assertIsCopyOf(file('build/libs/publish-2.jar'))

        and:
        progressLogging.uploadProgressLogged(module.ivy.uri)
        progressLogging.uploadProgressLogged(module.moduleMetadata.uri)
        progressLogging.uploadProgressLogged(module.jar.uri)

        where:
        authScheme << [AuthScheme.BASIC, AuthScheme.DIGEST, AuthScheme.NTLM]
    }

    @Unroll
    @ToBeFixedForInstantExecution
    def "reports failure publishing with #credsName credentials to authenticated repository using #authScheme auth"() {
        given:
        buildFile << """
            apply plugin: 'java'
            apply plugin: 'ivy-publish'
            version = '2'
            group = 'org.gradle'
            publishing {
                repositories {
                    ivy {
                        $creds
                        url "${ivyHttpRepo.uri}"
                    }
                }
                publications {
                    ivy(IvyPublication) {
                        from components.java
                    }
                }
            }
        """

        and:
        server.authenticationScheme = authScheme
        server.allowPut('/repo/org.gradle/publish/2/publish-2.jar', 'testuser', 'password')

        when:
        fails 'publish'

        then:
        failure.assertHasDescription('Execution failed for task \':publishIvyPublicationToIvyRepository\'.')
        failure.assertHasCause('Failed to publish publication \'ivy\' to repository \'ivy\'')
        failure.assertThatCause(CoreMatchers.containsString('Received status code 401 from server: Unauthorized'))

        where:
        authScheme        | credsName | creds
        AuthScheme.BASIC  | 'empty'   | ''
        AuthScheme.DIGEST | 'empty'   | ''
        AuthScheme.NTLM   | 'empty'   | ''
        AuthScheme.BASIC  | 'bad'     | BAD_CREDENTIALS
        AuthScheme.DIGEST | 'bad'     | BAD_CREDENTIALS
        AuthScheme.NTLM   | 'bad'     | BAD_CREDENTIALS
    }

    @ToBeFixedForInstantExecution
    def "reports failure publishing to HTTP repository"() {
        given:
        def repositoryPort = server.port

        buildFile << """
            apply plugin: 'java'
            apply plugin: 'ivy-publish'
            version = '2'
            group = 'org.gradle'
            publishing {
                repositories {
                    ivy {
                        url "${ivyHttpRepo.uri}"
                    }
                }
                publications {
                    ivy(IvyPublication) {
                        from components.java
                    }
                }
            }
        """

        and:
        server.addBroken("/")

        when:
        fails 'publish'

        then:
        failure.assertHasDescription('Execution failed for task \':publishIvyPublicationToIvyRepository\'.')
        failure.assertHasCause('Failed to publish publication \'ivy\' to repository \'ivy\'')
        failure.assertThatCause(CoreMatchers.containsString('Received status code 500 from server: broken'))

        when:
        server.stop()

        then:
        fails 'publish'

        and:
        failure.assertHasDescription('Execution failed for task \':publishIvyPublicationToIvyRepository\'.')
        failure.assertHasCause('Failed to publish publication \'ivy\' to repository \'ivy\'')
        failure.assertThatCause(matchesRegexp(".*?Connect to 127.0.0.1:${repositoryPort} (\\[.*\\])? failed: Connection refused.*"))
    }

    @ToBeFixedForInstantExecution
    def "uses first configured pattern for publication"() {
        given:
        buildFile << """
            apply plugin: 'java'
            apply plugin: 'ivy-publish'

            version = '2'
            group = 'org.gradle'
            publishing {
                repositories {
                    ivy {
                        artifactPattern "${ivyHttpRepo.artifactPattern}"
                        artifactPattern "http://localhost:${server.port}/alternative/[module]/[artifact]-[revision].[ext]"
                        ivyPattern "${ivyHttpRepo.ivyPattern}"
                        ivyPattern "http://localhost:${server.port}/secondary-ivy/[module]/ivy-[revision].xml"
                    }
                }
                publications {
                    ivy(IvyPublication) {
                        from components.java
                    }
                }
            }
        """

        and:
        module.jar.expectPut()
        module.jar.sha1.expectPut()
        module.jar.sha256.expectPut()
        module.jar.sha512.expectPut()
        module.ivy.expectPut()
        module.ivy.sha1.expectPut()
        module.ivy.sha256.expectPut()
        module.ivy.sha512.expectPut()

        when:
        run 'publish'

        then:
        module.assertIvyAndJarFilePublished()
        module.jarFile.assertIsCopyOf(file('build/libs/publish-2.jar'))

        outputContains "Publication of Gradle Module Metadata is disabled because you have configured an Ivy repository with a non-standard layout"
        !module.ivy.file.text.contains(MetaDataParser.GRADLE_6_METADATA_MARKER)
    }

    @ToBeFixedForInstantExecution
    void "can publish large artifact to authenticated repository"() {
        given:
        def largeJar = file("large.jar")
        new RandomAccessFile(largeJar, "rw").withCloseable {
            it.length = 1024 * 1024 * 10 // 10 mb
        }

        buildFile << """
            apply plugin: 'ivy-publish'

            version = '2'
            group = 'org.gradle'

            publishing {
                repositories {
                    ivy {
                        credentials {
                            username 'testuser'
                            password 'password'
                        }
                        url "${ivyHttpRepo.uri}"
                    }
                }
                publications {
                    ivy(IvyPublication) {
                        configurations {
                            runtime {
                                artifact('${largeJar.toURI()}') {
                                    name 'publish'
                                }
                            }
                        }
                    }
                }
            }
        """

        and:
        module.jar.expectPut('testuser', 'password')
        module.jar.sha1.expectPut('testuser', 'password')
        module.jar.sha256.expectPut('testuser', 'password')
        module.jar.sha512.expectPut('testuser', 'password')
        module.ivy.expectPut('testuser', 'password')
        module.ivy.sha1.expectPut('testuser', 'password')
        module.ivy.sha256.expectPut('testuser', 'password')
        module.ivy.sha512.expectPut('testuser', 'password')

        when:
        run 'publish'

        then:
        module.assertIvyAndJarFilePublished()
        module.ivyFile.assertIsFile()
        module.jarFile.assertIsCopyOf(new TestFile(largeJar))
    }

    @ToBeFixedForInstantExecution
    void "does not upload meta-data file if artifact upload fails"() {
        given:
        buildFile << """
            apply plugin: 'java'
            apply plugin: 'ivy-publish'

            version = '2'
            group = 'org.gradle'

            publishing {
                repositories {
                    ivy {
                        url "${ivyHttpRepo.uri}"
                    }
                }
                publications {
                    ivy(IvyPublication) {
                        from components.java
                    }
                }
            }
        """

        and:
        module.jar.expectPutBroken(HTTP_UNRECOVERABLE_ERROR)

        when:
        fails ':publish'

        then:
        module.jarFile.assertExists()
        module.ivyFile.assertDoesNotExist()
    }

    @ToBeFixedForInstantExecution
    def "retries artifact upload for transient network error"() {
        given:
        buildFile << """
            apply plugin: 'java'
            apply plugin: 'ivy-publish'

            version = '2'
            group = 'org.gradle'

            publishing {
                repositories {
                    ivy { url "${ivyHttpRepo.uri}" }
                }
                publications {
                    ivy(IvyPublication) {
                        from components.java
                    }
                }
            }
        """

        and:
        module.jar.expectPutBroken()
        module.jar.expectPutBroken()
        module.jar.expectPut()
        module.jar.sha1.expectPut()
        module.jar.sha256.expectPut()
        module.jar.sha512.expectPut()

        module.ivy.expectPutBroken()
        module.ivy.expectPut(HttpStatus.ORDINAL_201_Created)
        module.ivy.sha1.expectPut(HttpStatus.ORDINAL_201_Created)
        module.ivy.sha256.expectPut(HttpStatus.ORDINAL_201_Created)
        module.ivy.sha512.expectPut(HttpStatus.ORDINAL_201_Created)

        module.moduleMetadata.expectPutBroken()
        module.moduleMetadata.expectPut()
        module.moduleMetadata.sha1.expectPut()
        module.moduleMetadata.sha256.expectPut()
        module.moduleMetadata.sha512.expectPut()

        when:
        succeeds 'publish'

        then:
        module.assertMetadataAndJarFilePublished()
    }

    @ToBeFixedForInstantExecution
    @Unroll
    def "doesn't publish Gradle metadata if custom pattern is used"() {
        given:
        buildFile << """
            apply plugin: 'java'
            apply plugin: 'ivy-publish'

            version = '2'
            group = 'org.gradle'
            publishing {
                repositories {
                    ivy {
                        url "${ivyRepo.uri}"
                        patternLayout {
                           $layout
                        }
                    }
                }
                publications {
                    ivy(IvyPublication) {
                        from components.java
                    }
                }
            }
        """

        when:
        run 'publish'

        then:
        outputContains "Publication of Gradle Module Metadata is disabled because you have configured an Ivy repository with a non-standard layout"

        where:
        layout << [
            """
                artifact "org/foo/[revision]/[artifact](-[classifier]).[ext]"
                ivy "org/foo/[revision]/[artifact](-[classifier]).[ext]"
            """,
            'artifact "org/foo/[revision]/[artifact](-[classifier]).[ext]"'
        ]
    }

    @ToBeFixedForInstantExecution
    void "can publish artifact to authenticated repository using credentials provider"() {
        given:
        String credentialsBlock = """
            credentials(project.credentials.usernameAndPassword('ivyRepo'))
        """
        buildFile << publicationBuild('2', 'org.gradle', ivyHttpRepo.uri, credentialsBlock)

        and:
        PasswordCredentials credentials = new DefaultPasswordCredentials('username', 'password')

        module.jar.expectPut(credentials)
        module.jar.sha1.expectPut(credentials)
        module.jar.sha256.expectPut(credentials)
        module.jar.sha512.expectPut(credentials)
        module.ivy.expectPut(credentials)
        module.ivy.sha1.expectPut(credentials)
        module.ivy.sha256.expectPut(credentials)
        module.ivy.sha512.expectPut(credentials)
        module.moduleMetadata.expectPut(credentials)
        module.moduleMetadata.sha1.expectPut(credentials)
        module.moduleMetadata.sha256.expectPut(credentials)
        module.moduleMetadata.sha512.expectPut(credentials)

        when:
        executer.withArguments("-PivyRepoUsername=${credentials.username}", "-PivyRepoPassword=${credentials.password}")
        succeeds 'publish'

        then:
        module.assertMetadataAndJarFilePublished()
        module.jarFile.assertIsCopyOf(file('build/libs/publish-2.jar'))
    }

    @ToBeFixedForInstantExecution
    // TODO replace execution with configuration once this behavior is implemented
    def "fails at execution time with helpful error message when username and password provider has no value"() {
        given:
        String credentialsBlock = """
            credentials(project.credentials.usernameAndPassword('ivyRepo'))
        """
        buildFile << publicationBuild('2', 'org.gradle', ivyHttpRepo.uri, credentialsBlock)

        when:
        succeeds 'jar'

        and:
        succeeds 'tasks'

        and:
        fails 'publish'

        then:
        failure.assertHasDescription("Execution failed for task ':generateDescriptorFileForIvyPublication'.")

        // TODO
        //notExecuted(':jar', ':publishIvyPublicationToIvyRepository')
        //failure.assertHasDescription("Could not determine the dependencies of task ':publish'.")
        //failure.assertHasCause("Could not create task ':publishIvyPublicationToIvyRepository'.")
        // TODO where does 'configuredCredentialsProvider' name come from?
        failure.assertHasCause("Cannot query the value of property 'configuredCredentialsProvider' because it has no value available")
        failure.assertHasErrorOutput("The value of this property is derived from")
        failure.assertHasErrorOutput("- Gradle property 'ivyRepoUsername'")
        failure.assertHasErrorOutput("- Gradle property 'ivyRepoPassword'")
    }

    private static String publicationBuild(String version, String group, URI uri, String credentialsBlock) {
        return """
            plugins {
                id 'java'
                id 'ivy-publish'
            }
            version = '$version'
            group = '$group'

            publishing {
                repositories {
                    ivy {
                        url "$uri"
                        $credentialsBlock
                    }
                }
                publications {
                    ivy(IvyPublication) {
                        from components.java
                    }
                }
            }
            """
    }

}
