package com.example;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import javax.inject.Inject;

@Component(modules = MembersInjectionInterfaceMethod.Module1.class)
public interface MembersInjectionInterfaceMethod {
  void inject(Target instance);

  interface Target {
    @Inject void interfaceMethod(String one);
  }

  @Module
  abstract class Module1 {
    @Provides static String one() {
      return "one";
    }
  }
}
