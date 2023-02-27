package test.aop;

import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;

@Aspect
public class AspectJTest {
    
    @Pointcut("execution(* *.test(..))")
    public void test() {
    
    }
    
    @Before("test()")
    public void testBefore() {
        System.out.println("testBefore");
    }
    
    @After("test()")
    public void testAfter() {
        System.out.println("testAfter");
    }
    
    @Around("test()")
    public void testAround() {
        System.out.println("testAround");
    }
}
