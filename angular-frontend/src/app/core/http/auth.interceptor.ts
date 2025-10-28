import { HttpInterceptorFn } from '@angular/common/http';

// inteceptor used for tokens/cookies

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // attach bearer token if present
  const token = localStorage.getItem('access_token');
  const authReq = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;
  return next(authReq);
};
