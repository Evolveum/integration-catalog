export interface Request {
  id?: number;
  applicationId: string;
  capabilitiesType: string;
  requester: string;
}

export interface CreateRequest {
  applicationId: string;
  capabilitiesType: string;
  requester: string;
}
