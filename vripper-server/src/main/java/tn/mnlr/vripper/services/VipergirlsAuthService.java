package tn.mnlr.vripper.services;

import io.reactivex.processors.PublishProcessor;
import lombok.Getter;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import tn.mnlr.vripper.entities.Post;
import tn.mnlr.vripper.exception.VripperException;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class VipergirlsAuthService {

    private static final Logger logger = LoggerFactory.getLogger(VipergirlsAuthService.class);

    private final ConnectionManager cm;
    private final HtmlProcessorService htmlProcessorService;
    private final XpathService xpathService;
    private final AppSettingsService appSettingsService;
    private final CommonExecutor commonExecutor;

    @Getter
    private HttpClientContext context = HttpClientContext.create();

    @Getter
    private boolean authenticated = false;

    @Getter
    private String loggedUser;

    @Getter
    private PublishProcessor<String> loggedInUser = PublishProcessor.create();

    @Autowired
    public VipergirlsAuthService(ConnectionManager cm, HtmlProcessorService htmlProcessorService, XpathService xpathService, AppSettingsService appSettingsService, CommonExecutor commonExecutor) {
        this.cm = cm;
        this.htmlProcessorService = htmlProcessorService;
        this.xpathService = xpathService;
        this.appSettingsService = appSettingsService;
        this.commonExecutor = commonExecutor;
    }

    @PostConstruct
    private void init() {
        context.setCookieStore(new BasicCookieStore());
        try {
            authenticate();
        } catch (VripperException e) {
            logger.error("Cannot authenticate user with ViperGirls", e);
        }
    }

    public void authenticate() throws VripperException {

        logger.info("Authenticating using ViperGirls credentials");
        authenticated = false;

        if (!appSettingsService.getSettings().getVLogin()) {
            logger.debug("Authentication option is disabled");
            context.getCookieStore().clear();
            loggedUser = "";
            loggedInUser.onNext(loggedUser);
            return;
        }

        String username = appSettingsService.getSettings().getVUsername();
        String password = appSettingsService.getSettings().getVPassword();

        if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
            logger.error("Cannot authenticate with ViperGirls credentials, username or password is empty");
            context.getCookieStore().clear();
            loggedUser = "";
            loggedInUser.onNext(loggedUser);
            return;
        }

        HttpPost postAuth = cm.buildHttpPost("https://vipergirls.to/login.php?do=login");
        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("vb_login_username", username));
        params.add(new BasicNameValuePair("vb_login_password", ""));
        params.add(new BasicNameValuePair("vb_login_password_hint", "Password"));

        params.add(new BasicNameValuePair("cookieuser", "1"));
        params.add(new BasicNameValuePair("securitytoken", "guest"));
        params.add(new BasicNameValuePair("do", "login"));
        params.add(new BasicNameValuePair("vb_login_md5password", password));
        params.add(new BasicNameValuePair("vb_login_md5password_utf", password));
        try {
            postAuth.setEntity(new UrlEncodedFormEntity(params));
        } catch (Exception e) {
            context.getCookieStore().clear();
            loggedUser = "";
            loggedInUser.onNext(loggedUser);
            throw new VripperException(e);
        }

        postAuth.addHeader("Referer", "https://vipergirls.to/");
        postAuth.addHeader("Host", "vipergirls.to");

        CloseableHttpClient client = cm.getClient().build();

        try (CloseableHttpResponse response = client.execute(postAuth, context)) {

            if (response.getStatusLine().getStatusCode() / 100 != 2) {
                throw new VripperException(String.format("Unexpected response code returned %s", response.getStatusLine().getStatusCode()));
            }
            String responseBody = EntityUtils.toString(response.getEntity());
            logger.debug(String.format("Authentication with ViperGirls response body:%n%s", responseBody));
            EntityUtils.consumeQuietly(response.getEntity());
            if (context.getCookieStore().getCookies().stream().map(Cookie::getName).noneMatch(e -> e.equals("vg_userid"))) {
                throw new VripperException("Failed to authenticate user with ViperRipper");
            }
        } catch (Exception e) {
            context.getCookieStore().clear();
            loggedUser = "";
            loggedInUser.onNext(loggedUser);
            if (e instanceof VripperException) {
                throw (VripperException) e;
            } else {
                throw new VripperException(e);
            }
        }
        authenticated = true;
        loggedUser = username;
        logger.info(String.format("Authenticated: %s", username));
        loggedInUser.onNext(loggedUser);
    }

    public void leaveThanks(Post post) {
        if (!appSettingsService.getSettings().getVLogin()) {
            logger.debug("Authentication with ViperGirls option is disabled");
            return;
        }
        if (!appSettingsService.getSettings().getVThanks()) {
            logger.debug("Leave thanks option is disabled");
            return;
        }
        if (!authenticated) {
            logger.error("You are not authenticated");
            return;
        }
        if (post.getMetadata().get(Post.METADATA.THANKED.name()) != null && (boolean) post.getMetadata().get(Post.METADATA.THANKED.name())) {
            logger.debug("Already left a thanks");
            return;
        }
        commonExecutor.getGeneralExecutor().submit(() -> {
            try {
                postThanks(post, getSecurityToken(post.getUrl()));
            } catch (Exception e) {
                logger.error(String.format("Failed to leave a thanks for url %s, post id %s", post.getUrl(), post.getPostId()), e);
            }
        });
    }

    private String getSecurityToken(String url) throws VripperException {

        String securityToken;

        HttpGet httpGet = cm.buildHttpGet(url);
        httpGet.addHeader("Referer", "https://vipergirls.to/");
        httpGet.addHeader("Host", "vipergirls.to");

        CloseableHttpClient client = cm.getClient().build();

        try (CloseableHttpResponse response = client.execute(httpGet, context)) {

            String postPage = EntityUtils.toString(response.getEntity());
            Document document = htmlProcessorService.clean(postPage);

            String thanksUrl = xpathService
                    .getAsNode(document, "//li[contains(@id,'post_')][not(contains(@id,'post_thank'))]//a[@class='post_thanks_button']")
                    .getAttributes()
                    .getNamedItem("href")
                    .getTextContent()
                    .trim();

            securityToken = Arrays.stream(thanksUrl.split("&amp;"))
                    .filter(v -> v.startsWith("securitytoken"))
                    .findAny()
                    .orElse("")
                    .replace("securitytoken=", "");

            EntityUtils.consumeQuietly(response.getEntity());
        } catch (Exception e) {
            throw new VripperException(e);
        }
        return securityToken;
    }

    private void postThanks(Post post, String securityKey) throws VripperException {

        HttpPost postThanks = cm.buildHttpPost("https://vipergirls.to/post_thanks.php");
        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("do", "post_thanks_add"));
        params.add(new BasicNameValuePair("using_ajax", "1"));
        params.add(new BasicNameValuePair("p", post.getPostId()));
        params.add(new BasicNameValuePair("securitytoken", securityKey));
        try {
            postThanks.setEntity(new UrlEncodedFormEntity(params));
        } catch (Exception e) {
            throw new VripperException(e);
        }

        postThanks.addHeader("Referer", "https://vipergirls.to/");
        postThanks.addHeader("Host", "vipergirls.to");

        CloseableHttpClient client = cm.getClient().build();

        try (CloseableHttpResponse response = client.execute(postThanks, context)) {
            post.getMetadata().put(Post.METADATA.THANKED.name(), true);
            EntityUtils.consumeQuietly(response.getEntity());
        } catch (Exception e) {
            throw new VripperException(e);
        }
    }
}
