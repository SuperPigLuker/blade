/**
 * Copyright (c) 2017, biezhi 王爵 (biezhi.me@gmail.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blade.server.netty;

import com.blade.Blade;
import com.blade.Environment;
import com.blade.event.BeanProcessor;
import com.blade.event.EventType;
import com.blade.ioc.DynamicContext;
import com.blade.ioc.Ioc;
import com.blade.ioc.annotation.Bean;
import com.blade.ioc.annotation.Value;
import com.blade.ioc.bean.BeanDefine;
import com.blade.ioc.bean.ClassInfo;
import com.blade.ioc.bean.OrderComparator;
import com.blade.kit.*;
import com.blade.mvc.Const;
import com.blade.mvc.WebContext;
import com.blade.mvc.annotation.Path;
import com.blade.mvc.annotation.UrlPattern;
import com.blade.mvc.handler.DefaultExceptionHandler;
import com.blade.mvc.handler.ExceptionHandler;
import com.blade.mvc.hook.WebHook;
import com.blade.mvc.route.RouteBuilder;
import com.blade.mvc.route.RouteMatcher;
import com.blade.mvc.ui.template.DefaultEngine;
import com.blade.server.Server;
import com.blade.task.Task;
import com.blade.task.TaskContext;
import com.blade.task.TaskManager;
import com.blade.task.TaskStruct;
import com.blade.task.annotation.Schedule;
import com.blade.task.cron.CronExecutorService;
import com.blade.task.cron.CronExpression;
import com.blade.task.cron.CronThreadPoolExecutor;
import com.blade.watcher.EnvironmentWatcher;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ResourceLeakDetector;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.blade.kit.BladeKit.getPrefixSymbol;
import static com.blade.kit.BladeKit.getStartedSymbol;
import static com.blade.mvc.Const.*;

/**
 * Netty Web Server
 *
 * @author biezhi
 * 2017/5/31
 */
@Slf4j
public class NettyServer implements Server {

    private Blade               blade;
    private Environment         environment;
    private EventLoopGroup      bossGroup;
    private EventLoopGroup      workerGroup;
    private Channel             channel;
    private RouteBuilder        routeBuilder;
    private List<BeanProcessor> processors;
    private List<TaskStruct>    taskStruts = new ArrayList<>();
    private int                 padSize    = 26;

    private volatile boolean isStop;

    @Override
    public void start(Blade blade, String[] args) throws Exception {
        this.blade = blade;
        this.environment = blade.environment();
        this.processors = blade.processors();

        long initStart = System.currentTimeMillis();
        log.info("{} {}{}", StringKit.padRight("environment.jdk.version", padSize), getPrefixSymbol(), System.getProperty("java.version"));
        log.info("{} {}{}", StringKit.padRight("environment.user.dir", padSize), getPrefixSymbol(), System.getProperty("user.dir"));
        log.info("{} {}{}", StringKit.padRight("environment.java.io.tmpdir", padSize), getPrefixSymbol(), System.getProperty("java.io.tmpdir"));
        log.info("{} {}{}", StringKit.padRight("environment.user.timezone", padSize), getPrefixSymbol(), System.getProperty("user.timezone"));
        log.info("{} {}{}", StringKit.padRight("environment.file.encoding", padSize), getPrefixSymbol(), System.getProperty("file.encoding"));
        log.info("{} {}{}", StringKit.padRight("environment.classpath", padSize), getPrefixSymbol(), CLASSPATH);

        this.initConfig();

        String contextPath = environment.get(ENV_KEY_CONTEXT_PATH, "/");
        WebContext.init(blade, contextPath);

        this.initIoc();

        this.watchEnv();

        this.startServer(initStart);

        this.startTask();

        this.shutdownHook();
    }

    private void initIoc() {
        RouteMatcher routeMatcher = blade.routeMatcher();
        routeMatcher.initMiddleware(blade.middleware());

        routeBuilder = new RouteBuilder(routeMatcher);

        blade.scanPackages().stream()
                .flatMap(DynamicContext::recursionFindClasses)
                .map(ClassInfo::getClazz)
                .filter(ReflectKit::isNormalClass)
                .forEach(this::parseCls);

        routeMatcher.register();
//        routeBuilder.register();

        this.processors.stream().sorted(new OrderComparator<>()).forEach(b -> b.preHandle(blade));

        Ioc ioc = blade.ioc();
        if (BladeKit.isNotEmpty(ioc.getBeans())) {
            log.info("{}Register bean: {}", getStartedSymbol(), ioc.getBeans());
        }

        List<BeanDefine> beanDefines = ioc.getBeanDefines();
        if (BladeKit.isNotEmpty(beanDefines)) {
            beanDefines.forEach(b -> {
                BladeKit.injection(ioc, b);
                BladeKit.injectionValue(environment, b);
                List<TaskStruct> cronExpressions = BladeKit.getTasks(b.getType());
                if (null != cronExpressions) {
                    taskStruts.addAll(cronExpressions);
                }
            });
        }
        this.processors.stream().sorted(new OrderComparator<>()).forEach(b -> b.processor(blade));
    }

    private void startServer(long startTime) throws Exception {

        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);

        boolean SSL = environment.getBoolean(ENV_KEY_SSL, false);
        // Configure SSL.
        SslContext sslCtx = null;
        if (SSL) {
            String certFilePath       = environment.get(ENV_KEY_SSL_CERT, null);
            String privateKeyPath     = environment.get(ENE_KEY_SSL_PRIVATE_KEY, null);
            String privateKeyPassword = environment.get(ENE_KEY_SSL_PRIVATE_KEY_PASS, null);

            log.info("{}SSL CertChainFile  Path: {}", getStartedSymbol(), certFilePath);
            log.info("{}SSL PrivateKeyFile Path: {}", getStartedSymbol(), privateKeyPath);
            sslCtx = SslContextBuilder.forServer(new File(certFilePath), new File(privateKeyPath), privateKeyPassword).build();
        }

        // Configure the server.
        int backlog = environment.getInt(ENV_KEY_NETTY_SO_BACKLOG, 8192);

        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.SO_BACKLOG, backlog);
        b.option(ChannelOption.SO_REUSEADDR, true);
        b.childOption(ChannelOption.SO_REUSEADDR, true);

        int acceptThreadCount = environment.getInt(ENC_KEY_NETTY_ACCEPT_THREAD_COUNT, 1);
        int ioThreadCount     = environment.getInt(ENV_KEY_NETTY_IO_THREAD_COUNT, 0);

        // enable epoll
        if (BladeKit.epollIsAvailable()) {
            log.info("{}Use EpollEventLoopGroup", getStartedSymbol());
            b.option(EpollChannelOption.SO_REUSEPORT, true);

            NettyServerGroup nettyServerGroup = EpollKit.group(acceptThreadCount, ioThreadCount);
            this.bossGroup = nettyServerGroup.getBoosGroup();
            this.workerGroup = nettyServerGroup.getWorkerGroup();
            b.group(bossGroup, workerGroup).channel(nettyServerGroup.getSocketChannel());
        } else {
            log.info("{}Use NioEventLoopGroup", getStartedSymbol());

            this.bossGroup = new NioEventLoopGroup(acceptThreadCount, new NamedThreadFactory("boss@"));
            this.workerGroup = new NioEventLoopGroup(ioThreadCount, new NamedThreadFactory("worker@"));
            b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class);
        }

        b.handler(new LoggingHandler(LogLevel.DEBUG))
                .childHandler(new HttpServerInitializer(sslCtx, blade, bossGroup.next()));

        String address = environment.get(ENV_KEY_SERVER_ADDRESS, DEFAULT_SERVER_ADDRESS);
        int    port    = environment.getInt(ENV_KEY_SERVER_PORT, DEFAULT_SERVER_PORT);

        channel = b.bind(address, port).sync().channel();
        String appName = environment.get(ENV_KEY_APP_NAME, "Blade");

        log.info("{}{} initialize successfully, Time elapsed: {} ms", getStartedSymbol(), appName, (System.currentTimeMillis() - startTime));
        log.info("{}Blade start with {}", getStartedSymbol(), ColorKit.redAndWhite(address + ":" + port));
        log.info("{}Open browser access http://{}:{} ⚡\r\n", getStartedSymbol(), address.replace(DEFAULT_SERVER_ADDRESS, LOCAL_IP_ADDRESS), port);

        blade.eventManager().fireEvent(EventType.SERVER_STARTED, blade);
    }

    private void startTask() {
        if (taskStruts.size() > 0) {
            int                 corePoolSize    = environment.getInt(ENV_KEY_TASK_THREAD_COUNT, Runtime.getRuntime().availableProcessors() + 1);
            CronExecutorService executorService = TaskManager.getExecutorService();
            if (null == executorService) {
                executorService = new CronThreadPoolExecutor(corePoolSize, new NamedThreadFactory("task@"));
                TaskManager.init(executorService);
            }

            AtomicInteger jobCount = new AtomicInteger();

            for (TaskStruct taskStruct : taskStruts) {
                try {
                    Schedule schedule = taskStruct.getSchedule();
                    String   jobName  = StringKit.isBlank(schedule.name()) ? "task-" + jobCount.getAndIncrement() : schedule.name();
                    Task     task     = new Task(jobName, new CronExpression(schedule.cron()), schedule.delay());

                    TaskContext taskContext = new TaskContext(task);

                    task.setTask(() -> {
                        Object target = blade.ioc().getBean(taskStruct.getType());
                        Method method = taskStruct.getMethod();
                        try {
                            if (method.getParameterCount() == 1 && method.getParameterTypes()[0].equals(TaskContext.class)) {
                                taskStruct.getMethod().invoke(target, taskContext);
                            } else {
                                taskStruct.getMethod().invoke(target);
                            }
                        } catch (Exception e) {
                            log.warn("{}Task method error", getPrefixSymbol(), e.getMessage());
                        }
                    });

                    ScheduledFuture<?> future = executorService.submit(task);
                    task.setFuture(future);
                    TaskManager.addTask(task);
                } catch (Exception e) {
                    log.warn("{}Add task fail: {}", getPrefixSymbol(), e.getMessage());
                }
            }
        }
    }

    private void parseCls(Class<?> clazz) {
        if (null != clazz.getAnnotation(Bean.class) || null != clazz.getAnnotation(Value.class)) {
            blade.register(clazz);
        }
        if (null != clazz.getAnnotation(Path.class)) {
            if (null == blade.ioc().getBean(clazz)) {
                blade.register(clazz);
            }
            Object controller = blade.ioc().getBean(clazz);
            routeBuilder.addRouter(clazz, controller);
        }
        if (ReflectKit.hasInterface(clazz, WebHook.class) && null != clazz.getAnnotation(Bean.class)) {
            Object     hook       = blade.ioc().getBean(clazz);
            UrlPattern urlPattern = clazz.getAnnotation(UrlPattern.class);
            if (null == urlPattern) {
                routeBuilder.addWebHook(clazz, "/.*", hook);
            } else {
                Stream.of(urlPattern.values())
                        .forEach(pattern -> routeBuilder.addWebHook(clazz, pattern, hook));
            }
        }
        if (ReflectKit.hasInterface(clazz, BeanProcessor.class) && null != clazz.getAnnotation(Bean.class)) {
            this.processors.add((BeanProcessor) blade.ioc().getBean(clazz));
        }
        if (isExceptionHandler(clazz)) {
            ExceptionHandler exceptionHandler = (ExceptionHandler) blade.ioc().getBean(clazz);
            blade.exceptionHandler(exceptionHandler);
        }
    }

    private boolean isExceptionHandler(Class<?> clazz) {
        return (null != clazz.getAnnotation(Bean.class) && (
                ReflectKit.hasInterface(clazz, ExceptionHandler.class) || clazz.getSuperclass().equals(DefaultExceptionHandler.class)));
    }

    private void watchEnv() {
        boolean watchEnv = environment.getBoolean(ENV_KEY_APP_WATCH_ENV, true);
        log.info("{}Watched environment: {}", getStartedSymbol(), watchEnv, getStartedSymbol());

        if (watchEnv) {
            Thread t = new Thread(new EnvironmentWatcher());
            t.setName("watch@thread");
            t.start();
        }
    }

    private void initConfig() {

        if (null != blade.bootClass()) {
            blade.scanPackages(blade.bootClass().getPackage().getName());
        }

        // print banner text
        this.printBanner();

        String statics = environment.get(ENV_KEY_STATIC_DIRS, "");
        if (StringKit.isNotBlank(statics)) {
            blade.addStatics(statics.split(","));
        }

        String templatePath = environment.get(ENV_KEY_TEMPLATE_PATH, "templates");
        if (templatePath.charAt(0) == HttpConst.CHAR_SLASH) {
            templatePath = templatePath.substring(1);
        }
        if (templatePath.endsWith(HttpConst.SLASH)) {
            templatePath = templatePath.substring(0, templatePath.length() - 1);
        }
        DefaultEngine.TEMPLATE_PATH = templatePath;
    }

    private void shutdownHook() {
        Thread shutdownThread = new Thread(() -> stop());
        shutdownThread.setName("shutdown@thread");
        Runtime.getRuntime().addShutdownHook(shutdownThread);
    }

    @Override
    public void stop() {
        if (isStop) {
            return;
        }
        isStop = true;
        System.out.println();
        log.info("{}Blade shutdown ...", getStartedSymbol());
        try {
            WebContext.clean();
            if (this.bossGroup != null) {
                this.bossGroup.shutdownGracefully();
            }
            if (this.workerGroup != null) {
                this.workerGroup.shutdownGracefully();
            }
            log.info("{}Blade shutdown successful", getStartedSymbol());
        } catch (Exception e) {
            log.error("Blade shutdown error", e);
        }
    }

    @Override
    public void stopAndWait() {
        if (isStop) {
            return;
        }
        isStop = true;
        System.out.println();
        log.info("{}Blade shutdown ...", getStartedSymbol());
        try {
            if (this.bossGroup != null) {
                this.bossGroup.shutdownGracefully().sync();
            }
            if (this.workerGroup != null) {
                this.workerGroup.shutdownGracefully().sync();
            }
            log.info("{}Blade shutdown successful", getStartedSymbol());
        } catch (Exception e) {
            log.error("Blade shutdown error", e);
        }
    }

    @Override
    public void join() throws InterruptedException {
        channel.closeFuture().sync();
    }

    /**
     * print blade start banner text
     */
    private void printBanner() {
        if (null != blade.bannerText()) {
            System.out.println(blade.bannerText());
        } else {
            String text = Const.BANNER_TEXT + NEW_LINE +
                    StringKit.padLeft(" :: Blade :: (v", Const.BANNER_PADDING - 9) + Const.VERSION + ") " + NEW_LINE;
            System.out.println(ColorKit.magenta(text));
        }
    }

}
