// MDKeyPathObserver uygulaması; arayüzü kvo.def'de.
#import <Foundation/Foundation.h>

@interface MDKeyPathObserver : NSObject
- (nonnull instancetype)initWithTarget:(nonnull NSObject *)target
                               keyPath:(nonnull NSString *)keyPath
                              onChange:(void (^ _Nonnull)(id _Nonnull object))onChange;
- (void)invalidate;
@end

@implementation MDKeyPathObserver {
    __weak NSObject *_target;
    NSString *_keyPath;
    void (^_onChange)(id);
    BOOL _active;
}

static void *MDKeyPathObserverContext = &MDKeyPathObserverContext;

- (instancetype)initWithTarget:(NSObject *)target keyPath:(NSString *)keyPath onChange:(void (^)(id))onChange {
    if ((self = [super init])) {
        _target = target;
        _keyPath = [keyPath copy];
        _onChange = [onChange copy];
        [target addObserver:self forKeyPath:_keyPath options:NSKeyValueObservingOptionNew context:MDKeyPathObserverContext];
        _active = YES;
    }
    return self;
}

- (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    if (context != MDKeyPathObserverContext) {
        [super observeValueForKeyPath:keyPath ofObject:object change:change context:context];
        return;
    }
    if (_onChange && object) _onChange(object);
}

- (void)invalidate {
    if (!_active) return;
    _active = NO;
    [_target removeObserver:self forKeyPath:_keyPath context:MDKeyPathObserverContext];
    _onChange = nil;
}

- (void)dealloc {
    [self invalidate];
}
@end
