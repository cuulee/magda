//@flow
import ReactDocumentTitle from 'react-document-title';
import React from 'react';
import { config } from '../config.js';


import './AppContainer.css';

export default class AppContainer extends React.Component {
  render() {
    return (
      <ReactDocumentTitle title={config.appName}>
        <div>
        <h1>Welcome, {config.appName} Admin</h1>
        <div id='content' className='clearfix'>{this.props.children}</div>
        </div>
      </ReactDocumentTitle>
    );
  }
}