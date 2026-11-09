import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { fillBlankWeekdays } from '../../../pages/myhr/features/timesheets/timesheetQuickFill';
import { EntryWorkspace } from '../../../pages/myhr/features/timesheets/TimesheetsPage';
import { getApprovedDayEntries } from '../../../pages/myhr/features/timesheets/approvedTimesheetDays';

const dates=['2026-09-07','2026-09-08','2026-09-09','2026-09-10','2026-09-11'];
const today='2026-11-09';
it('fills a backdated selected week without depending on the current week',()=>{
  const result=fillBlankWeekdays({dates,today});
  expect(result.filledCount).toBe(5);
  expect(result.hours).toEqual(Object.fromEntries(dates.map(date=>[date,'8'])));
});
it('preserves existing hours, explicit zeros, notes, and non-work types',()=>{
  const hours={'2026-09-07':'4','2026-09-08':0,'2026-09-09':'','2026-09-10':'','2026-09-11':''};
  const result=fillBlankWeekdays({dates,today,hours,entryTypes:{'2026-09-09':'SICK_LEAVE'},descriptions:{'2026-09-10':'Client holiday'}});
  expect(result.filledCount).toBe(1);
  expect(result.hours).toEqual({...hours,'2026-09-11':'8'});
  expect(hours['2026-09-11']).toBe('');
});
it('skips weekends, future dates, holidays and locked approved dates',()=>{
  const result=fillBlankWeekdays({dates:['2026-11-06','2026-11-07','2026-11-08','2026-11-09','2026-11-10'],today,
    lockedDates:['2026-11-06'],entryTypes:{'2026-11-09':'HOLIDAY'}});
  expect(result.filledCount).toBe(0);
  expect(result.hours).toEqual({});
});
it('does not refill days on repeated taps',()=>{
  const first=fillBlankWeekdays({dates,today});
  expect(fillBlankWeekdays({dates,today,hours:first.hours})).toEqual({hours:first.hours,filledCount:0});
});
it('fills missing Monday while skipping approved Tuesday, Wednesday and zero-hour leave',()=>{
  const records=[{id:'approved',status:'APPROVED',entries:[{workDate:dates[1],hoursWorked:7},
    {workDate:dates[2],hoursWorked:9},{workDate:dates[3],hoursWorked:0,entryType:'SICK_LEAVE'}]}];
  const locked=getApprovedDayEntries(records);
  const result=fillBlankWeekdays({dates:dates.slice(0,4),today,lockedDates:Object.keys(locked)});
  expect(result).toEqual({filledCount:1,hours:{[dates[0]]:'8'}});
});
it('an explicit correction unlocks only the selected approved record',()=>{
  const records=[{id:'first',status:'APPROVED',entries:[{workDate:dates[0],hoursWorked:8}]},
    {id:'second',status:'APPROVED',entries:[{workDate:dates[1],hoursWorked:8}]}];
  expect(Object.keys(getApprovedDayEntries(records,'first'))).toEqual([dates[1]]);
});
it('keeps source-screen selected dates instead of resetting them to the current week',()=>{
  const setStep=vi.fn(), setRangeStart=vi.fn(), setRangeEnd=vi.fn();
  render(<EntryWorkspace step="source" today={today} rangeStart={dates[0]} rangeEnd={dates[4]} rangeDays={dates}
    rangeHours={{}} rangeDescriptions={{}} rangeEntryTypes={{}} supportingDocuments={[{id:'doc',fileName:'hours.pdf'}]}
    setStep={setStep} setRangeStart={setRangeStart} setRangeEnd={setRangeEnd} dateOrder="MDY" />);
  fireEvent.click(screen.getByRole('button',{name:'Choose dates for one-tap fill'}));
  expect(setStep).toHaveBeenCalledWith('dates');
  expect(setRangeStart).not.toHaveBeenCalled();
  expect(setRangeEnd).not.toHaveBeenCalled();
});
it('fills current hours in the editor and shows a review notice without submitting',()=>{
  const submit=vi.fn();
  function Editor(){
    const [hours,setHours]=useState({'2026-09-07':'6'});
    return <EntryWorkspace step="hours" today={today} rangeStart={dates[0]} rangeEnd={dates[1]} rangeDays={dates.slice(0,2)}
      rangeHours={hours} setRangeHours={setHours} rangeDescriptions={{}} rangeEntryTypes={{}} supportingDocuments={[{id:'doc',fileName:'hours.pdf'}]}
      onSubmit={submit} />;
  }
  render(<Editor />);
  fireEvent.click(screen.getByRole('button',{name:'Fill blank weekdays · 8h'}));
  expect(screen.getAllByRole('spinbutton',{name:'Hours'}).map(input=>input.value)).toEqual(['6','8']);
  expect(screen.getByRole('status')).toHaveTextContent('Added 8 hours to 1 blank weekday');
  expect(submit).not.toHaveBeenCalled();
});
