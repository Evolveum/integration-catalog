import { ComponentFixture, TestBed } from '@angular/core/testing';

import { RequestForm } from './request-form';

describe('RequestForm', () => {
  let component: RequestForm;
  let fixture: ComponentFixture<RequestForm>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RequestForm]
    })
    .compileComponents();

    fixture = TestBed.createComponent(RequestForm);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
