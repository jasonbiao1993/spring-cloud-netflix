# Zuul 源码分析
## 参考文章
[SpringCloud(Zuul)工作原理及源码分析之执行流程](http://www.songshuiyang.com/2019/08/07/backend/framework/spring/spring-cloud/analysis/SpringCloud(Zuul)%E5%B7%A5%E4%BD%9C%E5%8E%9F%E7%90%86%E5%8F%8A%E6%BA%90%E7%A0%81%E5%88%86%E6%9E%90%E4%B9%8B%E6%89%A7%E8%A1%8C%E6%B5%81%E7%A8%8B/)
[zuul 1.x 源码阅读之 ZuulServlet 及 Filter 加载](https://blog.csdn.net/lds2227/article/details/82819046)

Spring MVC一般都是通过@Controller注解的形式来定义其执行方法，Spring也提供通过实现接口的形式来定义其执行方法，Zuul就是通过实现Controller接口实现的